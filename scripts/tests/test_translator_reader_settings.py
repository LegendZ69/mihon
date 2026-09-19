"""Host-only Java guards for current inspector labels and journaled style autosave."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class ReaderSettingsTests(unittest.TestCase):
    def test_native_page_identity_and_interrupted_autosave_restoration(self):
        root = Path(__file__).resolve().parents[1] / "android-ui-validation"
        java_home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([
                str(java_home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-d", output,
                str(root / "src/app/mihon/validation/framework/ReaderSettingsPolicy.java"),
                str(root / "tests/ReaderSettingsPolicyTest.java"),
            ], check=True, capture_output=True)
            run = subprocess.run([
                str(java_home / "bin/java"), "-cp", output,
                "app.mihon.validation.framework.ReaderSettingsPolicyTest",
            ], check=True, capture_output=True, text=True)
            self.assertIn("GREEN 7 cases, 0 failures", run.stdout)


if __name__ == "__main__":
    unittest.main()
