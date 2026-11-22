param(
    [ValidateSet("MinApi26", "MinApi28")]
    [string]$Flavor = "MinApi28",
    [ValidateSet("CatalogTransaction", "CatalogStaging", "TranslationStaging", "TranslationTransaction")]
    [string]$Scenario = "CatalogTransaction",
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
$testRunner = "$testPackage/com.theskyc.nreader.TagBenchmarkRunner"
if ($Scenario -eq "CatalogTransaction") {
    $testClass = "com.theskyc.nreader.async.database.TagCatalogProcessRecoveryTest"
    $crashMethod = "$testClass#holdsCatalogTransactionForExternalProcessStop"
    $verifyMethod = "$testClass#previousCatalogAndLocalRuleSurviveProcessDeath"
    $crashPhase = "crash"
} elseif ($Scenario -eq "CatalogStaging") {
    $testClass = "com.theskyc.nreader.async.TagCatalogStagingRecoveryTest"
    $crashMethod = "$testClass#leavesPartialCatalogStageForExternalProcessStop"
    $verifyMethod = "$testClass#removesInterruptedCatalogStageAndInstallsVerifiedSnapshot"
    $crashPhase = "catalog-stage"
} elseif ($Scenario -eq "TranslationStaging") {
    $testClass = "com.theskyc.nreader.async.TagTranslationStagingRecoveryTest"
    $crashMethod = "$testClass#leavesPartialTranslationStageForExternalProcessStop"
    $verifyMethod = "$testClass#removesInterruptedTranslationStageAndInstallsVerifiedPack"
    $crashPhase = "stage"
} else {
    $testClass = "com.theskyc.nreader.async.database.TagTranslationTransactionRecoveryTest"
    $crashMethod = "$testClass#holdsTranslationTransactionForExternalProcessStop"
    $verifyMethod = "$testClass#previousTranslationSurvivesProcessDeathAndCanBeUpdated"
    $crashPhase = "translation-transaction"
}
$logDirectory = Join-Path $repoRoot "app\build\tag-recovery"
$userId = $null
$runnerProcess = $null
$cleanupProblem = $null
$installedPackages = @()

function Invoke-Adb([string[]]$Arguments) {
    $output = & $adb -s $serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $($output -join "`n")"
    }
    return $output -join "`n"
}

if (-not (Test-Path $targetApk) -or -not (Test-Path $testApk)) {
    throw "Build the $Flavor tagBenchmark and AndroidTest APKs before running this script"
}

$devices = @(& $adb devices | Select-String "^\S+\s+device(\s|$)" |
    ForEach-Object { ($_.Line -split '\s+')[0] })
if ($Serial) {
    if ($devices -cnotcontains $Serial) {
        throw "The requested Android device is not connected and ready: $Serial"
    }
} elseif ($devices.Count -eq 1) {
    $Serial = $devices[0]
} else {
    throw "Connect exactly one Android device or emulator, or select one with -Serial"
}
$sdkVersion = 0
$sdk = Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")
if (-not [int]::TryParse($sdk.Trim(), [ref]$sdkVersion) -or $sdkVersion -lt 26) {
    throw "Recovery tests require Android API 26 or newer"
}
# Android 9 and newer replace packages by default. On Android 9, -R must be
# the first option passed to the package manager.
$installOptions = @("install", "--no-incremental")
if ($sdkVersion -ge 28) {
    $installOptions += "-R"
}

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$token = [Guid]::NewGuid().ToString("N").Substring(0, 16)
$stdoutPath = Join-Path $logDirectory "instrument-$token.out"
$stderrPath = Join-Path $logDirectory "instrument-$token.err"
$userName = "NReaderTagRecovery-$token"

try {
    $createdUser = Invoke-Adb @("shell", "pm", "create-user", $userName)
    $match = [regex]::Match($createdUser, "created user id (\d+)")
    if (-not $match.Success) {
        throw "Could not determine the temporary Android user ID: $createdUser"
    }
    $userId = $match.Groups[1].Value

    Invoke-Adb @("shell", "am", "start-user", $userId) | Out-Null
    $deadline = (Get-Date).AddMinutes(2)
    do {
        $userState = Invoke-Adb @("shell", "am", "get-started-user-state", $userId)
        if ($userState.Trim() -eq "RUNNING_UNLOCKED") {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ($userState.Trim() -ne "RUNNING_UNLOCKED") {
        throw "Temporary Android user $userId did not unlock: $userState"
    }
    Invoke-Adb ($installOptions + @("--user", $userId, $targetApk)) | Out-Null
    $installedPackages += $targetPackage
    Invoke-Adb ($installOptions + @("--user", $userId, "-t", $testApk)) | Out-Null
    $installedPackages += $testPackage

    $crashArguments = @(
        "-s", $serial, "shell", "am", "instrument", "--user", $userId, "-w", "-r",
        "-e", "class", $crashMethod, "-e", "recoveryPhase", $crashPhase,
        "-e", "recoveryToken", $token, $testRunner
    )
    $runnerProcess = Start-Process -FilePath $adb -ArgumentList $crashArguments -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath

    $deviceProcessId = $null
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        if ($runnerProcess.HasExited) {
            throw "Recovery phase exited before reaching its ready marker. See $stdoutPath"
        }
        $log = Invoke-Adb @("logcat", "-d", "-s", "TagRecoveryProcessDeath:I")
        $marker = [regex]::Match($log, ('READY:' + $token + ':(\d+)\b'))
        if ($marker.Success) {
            $deviceProcessId = $marker.Groups[1].Value
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $deviceProcessId) {
        throw "Timed out waiting for the recovery marker. See $stdoutPath"
    }

    $packageProcess = "(?m)^[ \t]*$deviceProcessId[ \t]+$([regex]::Escape($targetPackage))[ \t]*\r?$"
    $processes = Invoke-Adb @("shell", "ps", "-A", "-o", "PID,NAME")
    if ($runnerProcess.HasExited -or $processes -notmatch $packageProcess) {
        throw "Prepared instrumentation process exited before force-stop. See $stdoutPath"
    }
    Invoke-Adb @("shell", "am", "force-stop", "--user", $userId, $targetPackage) | Out-Null
    Invoke-Adb @("shell", "am", "force-stop", "--user", $userId, $testPackage) | Out-Null
    if (-not $runnerProcess.WaitForExit(30000)) {
        Stop-Process -Id $runnerProcess.Id -Force
        throw "Instrumentation did not exit after force-stop. See $stdoutPath"
    }
    $runnerProcess = $null
    $processes = Invoke-Adb @("shell", "ps", "-A", "-o", "PID,NAME")
    $crashResult = Get-Content -LiteralPath $stdoutPath -Raw
    if ($processes -match $packageProcess -or
        $crashResult -notmatch "(?m)^INSTRUMENTATION_RESULT: shortMsg=Process crashed\.\r?$" -or
        $crashResult -notmatch "(?m)^INSTRUMENTATION_CODE: 0\r?$") {
        throw "Instrumentation did not report a terminated process after force-stop. See $stdoutPath"
    }

    $verification = Invoke-Adb @(
        "shell", "am", "instrument", "--user", $userId, "-w", "-r",
        "-e", "class", $verifyMethod, "-e", "recoveryPhase", "verify",
        "-e", "recoveryToken", $token, $testRunner
    )
    if ($verification -notmatch "(?m)^OK \(1 test\)\r?$" -or
        $verification -notmatch "(?m)^INSTRUMENTATION_CODE: -1\r?$" -or
        $verification -notmatch "(?m)^INSTRUMENTATION_STATUS_CODE: 0\r?$") {
        throw "Post-crash verification failed: $verification"
    }
}
finally {
    if ($userId -ne $null) {
        foreach ($package in $installedPackages) {
            try {
                Invoke-Adb @("shell", "am", "force-stop", "--user", $userId, $package) | Out-Null
            } catch {
                Write-Warning "Could not stop $package for temporary user $userId"
            }
            try {
                $removed = Invoke-Adb @("shell", "pm", "uninstall", "--user", $userId, $package)
                if ($removed.Trim() -ne "Success") {
                    throw $removed
                }
            } catch {
                $cleanupProblem = "Could not uninstall $package for $userName (ID $userId): $_"
                Write-Warning $cleanupProblem
            }
        }
        try {
            Invoke-Adb @("shell", "pm", "remove-user", $userId) | Out-Null
            $userPattern = "UserInfo\{$($userId):$([regex]::Escape($userName)):"
            $deadline = (Get-Date).AddSeconds(30)
            do {
                $users = Invoke-Adb @("shell", "pm", "list", "users")
                if ($users -notmatch $userPattern) {
                    break
                }
                Start-Sleep -Milliseconds 500
            } while ((Get-Date) -lt $deadline)
            if ($users -match $userPattern) {
                throw "Android still lists the temporary user"
            }
        } catch {
            $cleanupProblem = "Could not remove $userName (ID $userId): $_. Check users with adb -s $serial shell pm list users."
            Write-Warning $cleanupProblem
        }
    }
    if ($runnerProcess -ne $null -and -not $runnerProcess.HasExited) {
        Stop-Process -Id $runnerProcess.Id -Force
    }
}
if ($null -ne $cleanupProblem) {
    throw $cleanupProblem
}
Write-Output $verification
