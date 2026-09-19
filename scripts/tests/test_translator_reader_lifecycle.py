"""Actual Java lifecycle state machine; does not substitute for Android UI evidence."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class ReaderLifecycleTests(unittest.TestCase):
    def test_exactly_ten_real_activity_round_trips_fail_closed(self):
        root = Path(__file__).resolve().parents[1] / "android-ui-validation"
        java_home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([str(java_home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-d", output,
                            str(root / "src/app/mihon/validation/framework/ReaderLifecyclePolicy.java"),
                            str(root / "tests/ReaderLifecyclePolicyTest.java")], check=True, capture_output=True)
            result = subprocess.run([str(java_home / "bin/java"), "-cp", output,
                                     "app.mihon.validation.framework.ReaderLifecyclePolicyTest"],
                                    check=True, capture_output=True, text=True)
            self.assertIn("GREEN actual lifecycle policy", result.stdout)


if __name__ == "__main__":
    unittest.main()
