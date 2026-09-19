"""Host-only Java regression for deferred toolbar transitions; no Android or adb."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class ReaderControlsTests(unittest.TestCase):
    def test_deferred_classic_transition_waits_for_state_once_with_bounded_failure(self):
        root = Path(__file__).resolve().parents[1] / "android-ui-validation"
        java_home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        with tempfile.TemporaryDirectory() as temp:
            subprocess.run([str(java_home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options",
                            "-d", temp,
                            str(root / "src/app/mihon/validation/framework/UiStateTransition.java"),
                            str(root / "tests/UiStateTransitionTest.java")], check=True, capture_output=True)
            run = subprocess.run([str(java_home / "bin/java"), "-cp", temp,
                                  "app.mihon.validation.framework.UiStateTransitionTest"],
                                 check=True, capture_output=True, text=True)
            self.assertIn("RED old-policy simulation", run.stdout)
            self.assertIn("GREEN5cases", run.stdout)


if __name__ == "__main__":
    unittest.main()
