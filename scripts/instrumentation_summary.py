"""Summarize raw `am instrument -r` output as Markdown; exit non-zero on failures or a crash.

Usage: python3 -I scripts/instrumentation_summary.py <instrument.txt>
"""
from collections import Counter
from pathlib import Path
import sys

STATUS = {0: "passed", -1: "error", -2: "failed", -3: "ignored", -4: "skipped (assumption)"}


def blocks(lines):
    """Yield (status code, fields) for each status block; multi-line values continue until the next key."""
    fields, key = {}, None
    for line in lines:
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            yield int(line.split(":", 1)[1]), fields
            fields, key = {}, None
        elif line.startswith("INSTRUMENTATION_"):
            key = None
        elif key is not None:
            fields[key] += "\n" + line


def summarize(text):
    """Return (Markdown summary, exit status) for one `am instrument -r` output."""
    lines = text.splitlines()
    # One result per test; tests may also send their own status bundles, which carry other codes.
    results = {}
    for code, fields in blocks(lines):
        if code in STATUS and "test" in fields:
            results[(fields.get("class"), fields["test"])] = (code, fields)
    counts, problems = Counter(), []
    for (cls, test), (code, fields) in results.items():
        counts[STATUS[code]] += 1
        if code in (-1, -2):
            stack = fields.get("stack", "").strip().splitlines()
            problems.append((f"{cls}#{test}", stack[:12]))
    result = [line for line in lines if line.startswith(("INSTRUMENTATION_RESULT", "INSTRUMENTATION_CODE"))]
    tail = [line for line in lines if line.startswith(("OK (", "FAILURES!!!", "Tests run:"))]
    crashed = any("Process crashed" in line or "shortMsg" in line for line in result) or not any(
        line.startswith("INSTRUMENTATION_CODE") for line in result
    )

    out = ["## Instrumented tests\n", "| Result | Tests |\n|---|---|"]
    out += [f"| {name} | {counts.get(name, 0)} |" for name in ("passed", "failed", "error", "skipped (assumption)", "ignored")]
    out.append("")
    if tail:
        out.append("Runner: `" + " ".join(tail) + "`\n")
    if crashed:
        out.append("**The instrumentation did not finish normally:**\n")
        out.append("```\n" + "\n".join(result[-6:] or lines[-20:]) + "\n```\n")
    for name, stack in problems:
        out.append(f"### {name}\n\n```\n" + "\n".join(stack) + "\n```\n")
    return "\n".join(out), 1 if problems or crashed else 0


def main(path):
    summary, status = summarize(Path(path).read_text(encoding="utf-8", errors="replace"))
    print(summary)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
