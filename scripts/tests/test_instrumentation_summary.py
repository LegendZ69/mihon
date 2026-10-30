import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "instrumentation_summary", Path(__file__).resolve().parents[1] / "instrumentation_summary.py"
)
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


def status(cls, test, code, **extra):
    lines = [f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: test={test}"]
    lines += [f"INSTRUMENTATION_STATUS: {key}={value}" for key, value in extra.items()]
    return "\n".join(lines + [f"INSTRUMENTATION_STATUS_CODE: {code}"])


def run(*blocks, tail="OK (1 test)", code="-1"):
    return "\n".join(list(blocks) + ["INSTRUMENTATION_RESULT: stream=", tail, f"INSTRUMENTATION_CODE: {code}"])


class InstrumentationSummaryTest(unittest.TestCase):
    def test_counts_each_test_once_and_passes(self):
        text = run(
            status("a.T", "passes", 1),
            # A test's own sendStatus bundle carries no test key and its own code.
            "INSTRUMENTATION_STATUS: metric=42\nINSTRUMENTATION_STATUS_CODE: 2",
            status("a.T", "passes", 0),
            status("a.T", "gated", 1),
            status("a.T", "gated", -4),
            tail="OK (2 tests)",
        )
        markdown, code = summary.summarize(text)
        self.assertEqual(0, code)
        self.assertIn("| passed | 1 |", markdown)
        self.assertIn("| skipped (assumption) | 1 |", markdown)
        self.assertIn("Runner: `OK (2 tests)`", markdown)
        self.assertNotIn("did not finish", markdown)

    def test_failure_reports_its_multiline_stack(self):
        failing = status("a.T", "fails", -2, stack="java.lang.AssertionError: expected 1\n\tat a.T.fails(T.kt:9)")
        markdown, code = summary.summarize(run(status("a.T", "fails", 1), failing, tail="FAILURES!!!\nTests run: 1,  Failures: 1"))
        self.assertEqual(1, code)
        self.assertIn("| failed | 1 |", markdown)
        self.assertIn("### a.T#fails", markdown)
        self.assertIn("\tat a.T.fails(T.kt:9)", markdown)

    def test_process_crash_fails_even_without_a_failed_test(self):
        text = "\n".join([status("a.T", "crashes", 1), "INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "INSTRUMENTATION_CODE: 0"])
        markdown, code = summary.summarize(text)
        self.assertEqual(1, code)
        self.assertIn("Process crashed", markdown)

    def test_truncated_output_counts_as_not_finished(self):
        markdown, code = summary.summarize(status("a.T", "hangs", 1))
        self.assertEqual(1, code)
        self.assertIn("did not finish normally", markdown)


if __name__ == "__main__":
    unittest.main()
