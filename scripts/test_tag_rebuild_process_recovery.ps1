param(
    [ValidateSet("MinApi26", "MinApi28")]
    [string]$Flavor = "MinApi26",
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = (Get-Command adb -ErrorAction Stop).Source
$variant = $Flavor.ToLowerInvariant()
$targetApk = Join-Path $repoRoot "app\build\outputs\apk\$variant\tagBenchmark\app-$variant-tagBenchmark.apk"
$testApk = Join-Path $repoRoot "app\build\outputs\apk\androidTest\$variant\tagBenchmark\app-$variant-tagBenchmark-androidTest.apk"
$targetPackage = "com.theskyc.nreader.tagbenchmark"
$testPackage = "$targetPackage.test"
$runner = "$testPackage/com.theskyc.nreader.TagBenchmarkRunner"
$test = "com.theskyc.nreader.async.TagTranslationRebuildProcessSetupTest#holdsFailedRebuildForExternalProcessKill"
$probe = "$targetPackage/com.theskyc.nreader.TagRebuildRecoveryProbeActivity"
$token = [Guid]::NewGuid().ToString("N").Substring(0, 16)
$logDirectory = Join-Path $repoRoot "app\build\tag-recovery"
$stdoutPath = Join-Path $logDirectory "rebuild-$token.out"
$stderrPath = Join-Path $logDirectory "rebuild-$token.err"
$installedPackages = @()
$instrument = $null
$cleanupFailure = $null

function Invoke-Adb([string[]]$Arguments) {
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $($output -join "`n")"
    }
    return $output -join "`n"
}

if (!(Test-Path $targetApk) -or !(Test-Path $testApk)) {
    throw "Build the $Flavor tagBenchmark and AndroidTest APKs first"
}
if ($Serial -notmatch '^emulator-\d+$') {
    throw "Use the dedicated NReader_Tag_Validation_API26 or API28 emulator"
}
$avd = Invoke-Adb @("emu", "avd", "name")
if ($avd -notmatch '(?m)^NReader_Tag_Validation_API(26|28)\r?$') {
    throw "This test only runs on a dedicated NReader tag validation AVD"
}
if ((Invoke-Adb @("shell", "am", "get-current-user")).Trim() -ne "0") {
    throw "The dedicated AVD must use its owner profile"
}
$existing = Invoke-Adb @("shell", "pm", "list", "packages", "-u")
foreach ($package in @($targetPackage, $testPackage)) {
    if ($existing -match ("(?m)^package:" + [regex]::Escape($package) + "\r?$")) {
        throw "Benchmark package already exists: $package"
    }
}
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

try {
    foreach ($item in @(@($targetPackage, $targetApk), @($testPackage, $testApk))) {
        $installedPackages += $item[0]
        $result = Invoke-Adb @("install", "-t", $item[1])
        if ($result -notmatch '(?m)^Success\r?$') { throw "Installation failed: $result" }
    }
    $arguments = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", $test,
        "-e", "recoveryPhase", "rebuild-process", "-e", "recoveryToken", $token, $runner)
    $instrument = Start-Process -FilePath $adb -ArgumentList $arguments -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $oldPid = $null
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline -and !$instrument.HasExited) {
        $log = Invoke-Adb @("logcat", "-d", "-s", "TagRecoveryProcessDeath:I", "*:S")
        $marker = [regex]::Match($log, "READY:$token`:(\d+)\b")
        if ($marker.Success) { $oldPid = $marker.Groups[1].Value; break }
        Start-Sleep -Milliseconds 250
    }
    if (!$oldPid) { throw "Setup did not reach its marker; inspect $stdoutPath" }
    $log = Invoke-Adb @("logcat", "-d", "-v", "brief", "-s", "WM-WorkerWrapper:I", "*:S")
    $retry = [regex]::Match($log, "(?m)I/WM-WorkerWrapper\(\s*$oldPid\): Worker result RETRY for Work \[ id=([a-f0-9-]+), class=com\.theskyc\.nreader\.async\.TagTranslationKeyRebuildWorker")
    if (!$retry.Success -or $log -match "(?m)I/WM-WorkerWrapper\(\s*$oldPid\): Worker result SUCCESS for Work \[ id=$($retry.Groups[1].Value),") {
        throw "The original process did not have a pending retry"
    }
    $workId = $retry.Groups[1].Value
    $processes = Invoke-Adb @("shell", "ps", "-A", "-o", "PID,NAME")
    if ($processes -notmatch "(?m)^\s*$oldPid\s+$([regex]::Escape($targetPackage))\s*$") {
        throw "The setup process is no longer alive"
    }
    Invoke-Adb @("shell", "run-as", $targetPackage, "kill", "-9", $oldPid) | Out-Null
    if (!$instrument.WaitForExit(15000)) { throw "Instrumentation did not exit after process kill" }
    $processes = Invoke-Adb @("shell", "ps", "-A", "-o", "PID,NAME")
    if ($processes -match "(?m)^\s*$oldPid\s+") { throw "The original process is still alive" }
    $log = Invoke-Adb @("logcat", "-d", "-v", "brief", "-s", "WM-WorkerWrapper:I", "*:S")
    if ($log -match "(?m)I/WM-WorkerWrapper\(\s*$oldPid\): Worker result SUCCESS for Work \[ id=$workId,") {
        throw "The original process completed the rebuild before termination"
    }
    Invoke-Adb @("shell", "am", "start", "-W", "-n", $probe, "--es", "token", $token) | Out-Null
    $deadline = (Get-Date).AddSeconds(130)
    $result = $null
    while ((Get-Date) -lt $deadline) {
        $log = Invoke-Adb @("logcat", "-d", "-s", "TagRebuildProcessProbe:I", "*:S")
        $result = [regex]::Match($log, "(PASS|FAIL|ERROR|TIMEOUT):$token`:(\d+)(?::([a-f0-9-]+))?")
        if ($result.Success) { break }
        Start-Sleep -Milliseconds 500
    }
    if (!$result.Success -or $result.Groups[1].Value -ne "PASS" -or
        $result.Groups[2].Value -eq $oldPid -or $result.Groups[3].Value -ne $workId) {
        throw "Recovery probe failed or timed out: $($result.Value)"
    }
    $newPid = $result.Groups[2].Value
    $log = Invoke-Adb @("logcat", "-d", "-v", "brief", "-s", "WM-WorkerWrapper:I", "*:S")
    if ($log -notmatch "(?m)I/WM-WorkerWrapper\(\s*$newPid\): Worker result SUCCESS for Work \[ id=$workId,") {
        throw "The new process did not report completion of the original work"
    }
    Write-Output "PASS: process $oldPid -> $($result.Groups[2].Value), resumed work $workId"
} finally {
    if ($null -ne $instrument -and !$instrument.HasExited) { Stop-Process -Id $instrument.Id -Force }
    foreach ($package in $installedPackages) {
        try {
            $packages = Invoke-Adb @("shell", "pm", "list", "packages", "-u")
            if ($packages -match ("(?m)^package:" + [regex]::Escape($package) + "\r?$")) {
                $removed = Invoke-Adb @("uninstall", $package)
                if ($removed.Trim() -ne "Success") { throw $removed }
            }
        } catch {
            $cleanupFailure = "Could not remove $package from the dedicated AVD: $_"
            Write-Warning $cleanupFailure
        }
    }
    if ($cleanupFailure) { throw $cleanupFailure }
}
