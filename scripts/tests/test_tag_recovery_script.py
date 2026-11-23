import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "test_tag_process_recovery.ps1"
TARGET = "com.theskyc.nreader.tagbenchmark"
TEST_PACKAGE = TARGET + ".test"

FAKE_ADB = r'''
import json
import os
import sys
from pathlib import Path

path = Path(os.environ["FAKE_ADB_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
state["calls"].append(args)
code = 0
output = ""
if args == ["devices"]:
    output = "List of devices attached\nemulator-test\tdevice\n"
elif args[:2] == ["-s", "emulator-test"]:
    command = args[2:]
    if command == ["shell", "getprop", "ro.build.version.sdk"]:
        output = "26"
    elif command == ["shell", "pm", "list", "packages", "-u"]:
        output = "\n".join("package:" + name for name in state["existing"])
    elif command[:3] == ["shell", "pm", "create-user"]:
        state["user"] = True
        state["user_name"] = command[3]
        output = "Success: created user id 12"
    elif command[:3] == ["shell", "am", "start-user"]:
        output = "Success"
    elif command[:3] == ["shell", "am", "get-started-user-state"]:
        output = "RUNNING_UNLOCKED"
    elif command[0] == "install":
        package = state["target"] if "-t" not in command else state["test_package"]
        if package != state["fail_install"] or state["install_before_failure"]:
            state["installed"].append(package)
        if package == state["fail_install"]:
            code = 1
            output = "Simulated install transport failure after installation"
        else:
            output = "Success"
    elif command[:4] == ["shell", "pm", "list", "packages"]:
        output = "\n".join("package:" + name for name in state["installed"])
    elif command[:3] == ["shell", "am", "force-stop"]:
        output = ""
    elif command[:3] == ["shell", "pm", "uninstall"]:
        state["installed"].remove(command[-1])
        output = "Success"
    elif command[:3] == ["shell", "pm", "remove-user"]:
        state["user"] = False
        output = "Success: removed user"
    elif command == ["shell", "pm", "list", "users"]:
        output = "Users:\n\tUserInfo{0:Owner:4c13} running"
        if state["user"]:
            output += "\n\tUserInfo{12:" + state["user_name"] + ":0} running"
    else:
        code = 1
        output = "Unexpected command: " + repr(command)
else:
    code = 1
    output = "Unexpected adb arguments: " + repr(args)
path.write_text(json.dumps(state))
print(output)
sys.exit(code)
'''


@unittest.skipUnless(os.name == "nt" and shutil.which("powershell"), "requires Windows PowerShell")
class TagRecoveryScriptTest(unittest.TestCase):
    def run_script(self, existing=(), fail_install=TARGET, install_before_failure=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copyfile(SCRIPT, scripts / SCRIPT.name)
            for path in (
                "app/build/outputs/apk/minapi28/tagBenchmark/app-minapi28-tagBenchmark.apk",
                "app/build/outputs/apk/androidTest/minapi28/tagBenchmark/app-minapi28-tagBenchmark-androidTest.apk",
            ):
                apk = root / path
                apk.parent.mkdir(parents=True, exist_ok=True)
                apk.touch()
            fake = root / "fake_adb.py"
            fake.write_text(FAKE_ADB, encoding="utf-8")
            (root / "adb.cmd").write_text(f'@"{sys.executable}" "{fake}" %*\r\n', encoding="utf-8")
            state_path = root / "state.json"
            state_path.write_text(json.dumps({
                "calls": [], "existing": list(existing), "installed": [], "user": False,
                "target": TARGET, "test_package": TEST_PACKAGE, "fail_install": fail_install,
                "install_before_failure": install_before_failure,
            }))
            env = os.environ.copy()
            env["PATH"] = str(root) + os.pathsep + env["PATH"]
            env["FAKE_ADB_STATE"] = str(state_path)
            result = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-File", str(scripts / SCRIPT.name)],
                capture_output=True, text=True, env=env, timeout=30,
            )
            return result, json.loads(state_path.read_text())

    def test_existing_package_prevents_creating_a_user_or_installing(self):
        for package in (TARGET, TEST_PACKAGE):
            with self.subTest(package=package):
                result, state = self.run_script(existing=[package])
                self.assertNotEqual(0, result.returncode)
                self.assertIn("already exists", result.stderr)
                self.assertFalse(state["user"])
                self.assertFalse(any("create-user" in call or "install" in call for call in state["calls"]))

    def test_failed_install_cleans_packages_that_were_actually_installed(self):
        for package in (TARGET, TEST_PACKAGE):
            with self.subTest(package=package):
                result, state = self.run_script(fail_install=package)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Simulated install transport failure", result.stderr)
                self.assertEqual([], state["installed"])
                self.assertFalse(state["user"])
                self.assertTrue(any("uninstall" in call and package in call for call in state["calls"]))

    def test_failed_install_without_a_package_does_not_attempt_uninstall(self):
        result, state = self.run_script(install_before_failure=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Simulated install transport failure", result.stderr)
        self.assertEqual([], state["installed"])
        self.assertFalse(state["user"])
        self.assertFalse(any("uninstall" in call for call in state["calls"]))


if __name__ == "__main__":
    unittest.main()
