"""Host-only actual Java canonical reset policy; no Android or device actions."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class FrameViewportTests(unittest.TestCase):
    def test_primed_anchor_schedule_preserves_full_body_and_cancels_interrupted_input(self):
        root = Path(__file__).resolve().parents[1] / "android-ui-validation"
        java_home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        with tempfile.TemporaryDirectory() as output:
            sources = [root / "src/app/mihon/validation/framework" / name for name in
                       ("FrameAnchorPolicy.java", "FrameAnchorMotion.java")]
            subprocess.run([str(java_home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-d", output,
                            *map(str, sources), str(root / "tests/FrameAnchorMotionTest.java")], check=True, capture_output=True)
            result = subprocess.run([str(java_home / "bin/java"), "-cp", output,
                                     "app.mihon.validation.framework.FrameAnchorMotionTest"], check=True, capture_output=True, text=True)
            self.assertIn("GREEN primed anchor schedule", result.stdout)

    def test_interior_anchor_policy_is_scoped_repeatable_and_nonadaptive(self):
        root = Path(__file__).resolve().parents[1] / "android-ui-validation"
        java_home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([str(java_home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-d", output,
                            str(root / "src/app/mihon/validation/framework/FrameAnchorPolicy.java"),
                            str(root / "tests/FrameAnchorPolicyTest.java")], check=True, capture_output=True)
            result = subprocess.run([str(java_home / "bin/java"), "-cp", output,
                                     "app.mihon.validation.framework.FrameAnchorPolicyTest"], check=True, capture_output=True, text=True)
            self.assertIn("GREEN8 anchor policy", result.stdout)

    def test_same_page_offset_requires_observed_adjacent_and_return(self):
        root = Path(__file__).resolve().parents[1] / "android-ui-validation"
        java_home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([str(java_home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-d", output,
                            str(root / "src/app/mihon/validation/framework/FrameViewportReset.java"),
                            str(root / "tests/FrameViewportResetTest.java")], check=True, capture_output=True)
            result = subprocess.run([str(java_home / "bin/java"), "-cp", output,
                                     "app.mihon.validation.framework.FrameViewportResetTest"], check=True, capture_output=True, text=True)
            self.assertIn("RED old same-page simulation", result.stdout)
            self.assertIn("GREEN8cases", result.stdout)


if __name__ == "__main__":
    unittest.main()
