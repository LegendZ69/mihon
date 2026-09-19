#!/usr/bin/env python3
"""Fork-only release contracts. Importing this module performs no remote or provider work."""
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

REPOSITORY = "LegendZ69/mihon"
RELEASE_BRANCH = "codex/manga-translator"
UPSTREAM = "https://github.com/mihonapp/mihon.git"
FIRST_NUMBER = 15
MAX_NUMBER = 2_099_900_000
TAG = re.compile(r"translator-v([1-9][0-9]*)\Z")
SHA = re.compile(r"[0-9a-f]{40}\Z")
CERT = "e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9"


class ReleaseError(RuntimeError):
    pass


def command(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    # Output is returned to callers, never dumped with environment values or raw HTTP bodies.
    result = subprocess.run(args, cwd=repo, text=True, capture_output=True)
    if check and result.returncode:
        raise ReleaseError(f"{args[0]} {args[1] if len(args) > 1 else ''} failed (exit {result.returncode})")
    return result


def git(repo: Path, *args: str) -> str:
    return command(repo, "git", *args).stdout.strip()


def commit_sha(repo: Path, ref: str) -> str:
    if ref.startswith("-"):
        raise ReleaseError("Invalid revision")
    value = git(repo, "rev-parse", "--verify", f"{ref}^{{commit}}")
    if not SHA.fullmatch(value):
        raise ReleaseError("Expected a complete Git commit SHA")
    return value


@dataclasses.dataclass(frozen=True)
class Reservation:
    number: int
    source_sha: str
    upstream_sha: str
    schema: int = 1

    @property
    def tag(self) -> str:
        return f"translator-v{self.number}"

    @property
    def version_code(self) -> int:
        return 100_000 + self.number

    def validate(self) -> None:
        if self.schema != 1 or not FIRST_NUMBER <= self.number <= MAX_NUMBER:
            raise ReleaseError("Unsupported release reservation/version number")
        if not SHA.fullmatch(self.source_sha) or not SHA.fullmatch(self.upstream_sha):
            raise ReleaseError("Reservation contains an invalid commit SHA")


def reservations(repo: Path) -> list[Reservation]:
    found = []
    sources = set()
    for tag in git(repo, "tag", "--list", "translator-v*").splitlines():
        match = TAG.fullmatch(tag)
        if not match:
            raise ReleaseError("Unexpected tag in reserved translator-v namespace")
        if git(repo, "cat-file", "-t", f"refs/tags/{tag}") != "tag":
            raise ReleaseError(f"{tag} must be an annotated reservation")
        try:
            payload = json.loads(git(repo, "for-each-ref", "--format=%(contents)", f"refs/tags/{tag}"))
            reservation = Reservation(**payload)
        except (ValueError, TypeError) as error:
            raise ReleaseError(f"{tag} has an invalid reservation") from error
        reservation.validate()
        if reservation.number != int(match[1]) or commit_sha(repo, tag) != reservation.source_sha:
            raise ReleaseError(f"{tag} reservation does not match its source commit")
        identity = (reservation.source_sha, reservation.upstream_sha)
        if identity in sources:
            raise ReleaseError("One source/upstream pair has multiple release reservations")
        sources.add(identity)
        found.append(reservation)
    return sorted(found, key=lambda item: item.number)


def reserve_local(repo: Path, source: str, upstream: str, number: int | None = None) -> Reservation:
    """Create an immutable local annotated tag; never pushes or overwrites any tag."""
    source_sha = commit_sha(repo, source)
    upstream_sha = commit_sha(repo, upstream)
    if command(repo, "git", "merge-base", "--is-ancestor", upstream_sha, source_sha, check=False).returncode:
        raise ReleaseError("Release source does not contain the recorded upstream commit")
    existing = reservations(repo)
    same = next((item for item in existing if item.source_sha == source_sha and item.upstream_sha == upstream_sha), None)
    if same:
        if number is not None and same.number != number:
            raise ReleaseError("Existing reservation is immutable")
        return same
    next_number = max([FIRST_NUMBER - 1] + [item.number for item in existing]) + 1
    if number is not None and number != next_number:
        raise ReleaseError(f"Next unreserved version is {next_number}")
    result = Reservation(next_number, source_sha, upstream_sha)
    result.validate()
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as message:
        json.dump(dataclasses.asdict(result), message, sort_keys=True)
        message.flush()
        git(repo, "tag", "--annotate", result.tag, source_sha, "--file", message.name)
    return result


def merge_upstream(repo: Path, branch: str, upstream: str) -> str:
    """Merge without discarding translator changes. Conflicts restore the unchanged branch."""
    if branch != RELEASE_BRANCH:
        raise ReleaseError("Only the translator branch may be merged for releases")
    if git(repo, "status", "--porcelain", "--untracked-files=no"):
        raise ReleaseError("Refusing to merge a dirty tracked working tree")
    upstream_sha = commit_sha(repo, upstream)
    git(repo, "checkout", branch)
    result = command(repo, "git", "merge", "--no-edit", upstream_sha, check=False)
    if result.returncode:
        command(repo, "git", "merge", "--abort", check=False)
        raise ReleaseError("Upstream merge failed or has conflicts; translator history was not overwritten")
    return commit_sha(repo, "HEAD")


def require_fork_context() -> None:
    if os.environ.get("GITHUB_REPOSITORY", REPOSITORY) != REPOSITORY:
        raise ReleaseError("This automation is restricted to the configured fork")
    if os.environ.get("GITHUB_REF", f"refs/heads/{RELEASE_BRANCH}") != f"refs/heads/{RELEASE_BRANCH}":
        raise ReleaseError("Release automation must run from the translator branch")


def gh(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return command(repo, "gh", *args, check=check)


def release_metadata(repo: Path, tag: str) -> dict | None:
    def validate(value: object) -> dict:
        if not isinstance(value, dict) or type(value.get("id")) is not int or value["id"] <= 0:
            raise ReleaseError("GitHub returned an invalid release identity")
        if not isinstance(value.get("tag_name"), str) or not value["tag_name"]:
            raise ReleaseError("GitHub returned an invalid release tag")
        if type(value.get("draft")) is not bool or type(value.get("prerelease")) is not bool:
            raise ReleaseError("GitHub returned invalid release publication flags")
        if not isinstance(value.get("assets"), list) or any(not isinstance(asset, dict) for asset in value["assets"]):
            raise ReleaseError("GitHub returned invalid release assets")
        return value

    def decode(body: str) -> object:
        try:
            return json.loads(body)
        except ValueError as error:
            raise ReleaseError("GitHub returned malformed release metadata") from error

    # gh api prints status only on stderr here; no HTTP response body is logged.
    result = gh(repo, "api", f"repos/{REPOSITORY}/releases/tags/{tag}", check=False)
    if result.returncode:
        if "HTTP 404" not in result.stderr:
            raise ReleaseError("Could not inspect GitHub release; refusing to assume it is absent")
        # The tag endpoint may hide drafts. Authenticated release listings include drafts for
        # callers with push access: https://docs.github.com/en/rest/releases/releases#list-releases
        listing = gh(repo, "api", f"repos/{REPOSITORY}/releases?per_page=100", "--paginate", "--slurp", check=False)
        if listing.returncode:
            raise ReleaseError("Could not list GitHub releases; refusing to assume the draft is absent")
        pages = decode(listing.stdout)
        if not isinstance(pages, list) or not pages or any(not isinstance(page, list) for page in pages):
            raise ReleaseError("GitHub returned invalid release pagination")
        matches = []
        identities = set()
        for page in pages:
            for item in page:
                value = validate(item)
                if value["id"] in identities:
                    raise ReleaseError("GitHub returned duplicate release identities")
                identities.add(value["id"])
                if value["tag_name"] == tag:
                    matches.append(value)
        if len(matches) > 1:
            raise ReleaseError("GitHub returned duplicate releases for the reserved tag")
        return matches[0] if matches else None
    value = validate(decode(result.stdout))
    if value.get("tag_name") != tag:
        raise ReleaseError("GitHub returned a different release tag")
    return value


def same_release_inputs(repo: Path, left: str, right: str) -> bool:
    changed = git(repo, "diff", "--name-only", "-z", left, right, "--").split("\0")
    return all(not name or name.startswith("docs/") or name.lower().endswith(".md") for name in changed)


def equivalent_reservation(repo: Path, source: str, upstream: str) -> Reservation | None:
    source_sha = commit_sha(repo, source)
    upstream_sha = commit_sha(repo, upstream)
    history = reservations(repo)
    latest = history[-1] if history else None
    if latest and latest.upstream_sha == upstream_sha and same_release_inputs(repo, latest.source_sha, source_sha):
        return latest
    return None


def build_needed(existing: Reservation | None, published: bool, retry_reserved: bool) -> bool:
    if published:
        return False
    return existing is None or retry_reserved


def mirror_main(repo: Path, upstream: str) -> None:
    """Advance the mirror only by fast-forward; divergent histories remain untouched."""
    upstream_sha = commit_sha(repo, upstream)
    previous = commit_sha(repo, "refs/remotes/origin/main")
    if previous == upstream_sha:
        return
    if command(repo, "git", "merge-base", "--is-ancestor", previous, upstream_sha, check=False).returncode:
        raise ReleaseError("main has diverged from upstream; automatic synchronization cannot rewrite either history")
    git(repo, "push", "origin", f"{upstream_sha}:refs/heads/main")


def sync_and_reserve(repo: Path, retry_reserved: bool = False) -> tuple[Reservation, bool]:
    """Remote mutations are limited to main mirroring, a normal branch merge and a new tag."""
    require_fork_context()
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise ReleaseError("sync is restricted to a disposable GitHub Actions checkout")
    if os.environ.get("TRANSLATOR_RELEASE_AUTOMATION_ENABLED") != "true":
        raise ReleaseError("Release automation is disabled by repository configuration")
    if git(repo, "remote", "get-url", "origin").rstrip("/").removesuffix(".git") != f"https://github.com/{REPOSITORY}":
        raise ReleaseError("origin must be the configured HTTPS fork URL")
    default = gh(repo, "api", f"repos/{REPOSITORY}", "--jq", ".default_branch").stdout.strip()
    if default != RELEASE_BRANCH:
        raise ReleaseError("Set the translator branch as default before enabling scheduled releases")
    git(repo, "fetch", "--no-tags", "origin",
        "+refs/heads/main:refs/remotes/origin/main",
        f"+refs/heads/{RELEASE_BRANCH}:refs/remotes/origin/{RELEASE_BRANCH}",
        "refs/tags/translator-v*:refs/tags/translator-v*")
    git(repo, "fetch", "--no-tags", UPSTREAM, "+refs/heads/main:refs/remotes/translator-upstream/main")
    upstream_sha = commit_sha(repo, "refs/remotes/translator-upstream/main")
    source_before = commit_sha(repo, f"refs/remotes/origin/{RELEASE_BRANCH}")
    try:
        mirror_main(repo, upstream_sha)
    except ReleaseError:
        record_blocked_sync(repo, source_before, upstream_sha)
        raise
    if git(repo, "status", "--porcelain", "--untracked-files=no"):
        raise ReleaseError("Refusing to replace a dirty checkout")
    git(repo, "checkout", "-B", RELEASE_BRANCH, f"refs/remotes/origin/{RELEASE_BRANCH}")
    try:
        source = merge_upstream(repo, RELEASE_BRANCH, upstream_sha)
    except ReleaseError:
        record_blocked_sync(repo, source_before, upstream_sha)
        raise
    git(repo, "push", "origin", f"HEAD:refs/heads/{RELEASE_BRANCH}")
    if not remote_heads_current(repo, source, upstream_sha):
        raise ReleaseError("Source or upstream advanced during synchronization; the next run will resynchronize")
    existing = equivalent_reservation(repo, source, upstream_sha)
    record = existing or reserve_local(repo, source, upstream_sha)
    if existing is None:
        # No force: a race is a hard failure, never a reused version or moved source tag.
        git(repo, "push", "origin", f"refs/tags/{record.tag}:refs/tags/{record.tag}")
    remote_release = release_metadata(repo, record.tag)
    published = remote_release is not None and not remote_release.get("draft", False)
    return record, build_needed(existing, published, retry_reserved)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_apk_contract(apk: Path, record: Reservation, signer: str, badging: str) -> dict:
    certificates = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signer)
    if len(certificates) != 1 or certificates[0].lower() != CERT:
        raise ReleaseError("APK certificate differs from the preserved installation certificate")
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != "app.mihon" or int(package[2]) != record.version_code:
        raise ReleaseError("APK application ID or versionCode does not match the reservation")
    if not package[3].endswith(f"-translator.{record.number}") or "application-debuggable" in badging:
        raise ReleaseError("Expected the normal minified release variant and translator version name")
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
    if abis != {"arm64-v8a"}:
        raise ReleaseError("Public APK must contain only the arm64-v8a native ABI")
    return {"application_id": package[1], "version_code": int(package[2]), "version_name": package[3],
            "certificate_sha256": CERT, "abi": "arm64-v8a", "variant": "release"}


def validation_summary(reports: list[Path], formatting: str, migrations: str, unit_status: str, at: str) -> dict:
    allowed = {"passed", "failed", "not_run"}
    if any(value not in allowed for value in (formatting, migrations, unit_status)):
        raise ReleaseError("Invalid validation status")
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "reports": 0}
    for report in sorted(set(reports)):
        if report.stat().st_size > 8 * 1024 * 1024:
            raise ReleaseError("Unit-test XML exceeds the summary input limit")
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for field in ("tests", "failures", "errors", "skipped"):
                value = int(suite.get(field, "0"))
                if value < 0:
                    raise ReleaseError("Invalid unit-test counts")
                totals[field] += value
            totals["reports"] += 1
    if unit_status == "passed" and totals["reports"] and (totals["failures"] or totals["errors"]):
        raise ReleaseError("Unit-test XML contradicts a passing status")
    return {
        "schema": 1, "recorded_at": at,
        "formatting": {"status": formatting}, "sqldelight_migrations": {"status": migrations},
        "unit_tests": {"status": unit_status, "counts_scope": "supplied JUnit reports",
                       "totals": totals if totals["reports"] else None},
        "device_validation": {"status": "not_run", "reason": "No device validation is performed by this release workflow"},
        "live_providers": {"status": "not_run", "reason": "No provider calls are made during release automation"},
        "human_passage_approval": {"status": "not_run"},
        "disclosure": "Aggregate results only. Raw logs, test names, payloads and credentials are excluded.",
    }


def device_summary(value: dict, record: Reservation, apk_digest: str) -> dict:
    """Accept only an explicitly supplied receipt for this exact source/APK, without free-form data."""
    if (not isinstance(value, dict) or set(value) != {"schema", "source_sha", "apk_sha256", "runs"} or
            value["schema"] != 1 or value["source_sha"] != record.source_sha or value["apk_sha256"] != apk_digest):
        raise ReleaseError("Device receipt must identify this exact source and APK hash")
    runs = value["runs"]
    fields = {"kind", "page_size_bytes", "status", "tests", "failures", "errors", "skipped", "recorded_at",
              "tested_package", "tested_apk_sha256", "instrumentation_apk_sha256"}
    if not isinstance(runs, list) or not 1 <= len(runs) <= 32:
        raise ReleaseError("Device receipt must contain 1–32 bounded test runs")
    for run in runs:
        if not isinstance(run, dict) or set(run) != fields:
            raise ReleaseError("Device receipt cannot include unstructured content")
        if not isinstance(run["tested_package"], str) or run["tested_package"] not in {"app.mihon", "app.mihon.benchmark"}:
            raise ReleaseError("Device run must identify the released app or its benchmark companion")
        for field in ("tested_apk_sha256", "instrumentation_apk_sha256"):
            if not isinstance(run[field], str) or not re.fullmatch(r"[0-9a-f]{64}", run[field]):
                raise ReleaseError("Device run requires exact lowercase SHA-256 hashes for both tested and instrumentation APKs")
        if run["tested_package"] == "app.mihon" and run["tested_apk_sha256"] != apk_digest:
            raise ReleaseError("A directly instrumented main APK must match the released artifact hash")
        if run["kind"] not in {"k90", "emulator16k"} or run["status"] not in {"passed", "failed", "not_run"}:
            raise ReleaseError("Invalid device validation kind/status")
        if type(run["page_size_bytes"]) is not int or run["page_size_bytes"] not in {4096, 16384}:
            raise ReleaseError("Device receipt requires an observed kernel page size")
        if run["kind"] == "emulator16k" and run["page_size_bytes"] != 16384:
            raise ReleaseError("16 KB evidence requires an actual 16384-byte runtime")
        if any(type(run[field]) is not int or not 0 <= run[field] <= 1_000_000
               for field in ("tests", "failures", "errors", "skipped")):
            raise ReleaseError("Invalid device test counts")
        if sum(run[field] for field in ("failures", "errors", "skipped")) > run["tests"]:
            raise ReleaseError("Device counts exceed the recorded tests")
        if run["status"] == "passed" and (run["tests"] == 0 or run["failures"] or run["errors"]):
            raise ReleaseError("Device counts contradict a passing status")
        at = run["recorded_at"]
        if not isinstance(at, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", at):
            raise ReleaseError("Device run time must be a UTC timestamp")
        try:
            dt.datetime.strptime(at, "%Y-%m-%dT%H:%M:%SZ")
        except ValueError as error:
            raise ReleaseError("Invalid device run time") from error
    failed = any(run["status"] == "failed" for run in runs)
    passed_kinds = {run["kind"] for run in runs if run["status"] == "passed"}
    status = "failed" if failed else ("passed" if passed_kinds == {"k90", "emulator16k"} else "partial")
    return {"status": status,
            "scope": "Focused native tests apply to each listed tested package/APK and instrumentation APK. "
                     "The top-level apk_sha256 identifies the released app.mihon artifact. "
                     "app.mihon.benchmark entries cover the minified benchmark companion.",
            "source_sha": record.source_sha, "apk_sha256": apk_digest, "runs": runs}


def bundle(repo: Path, record: Reservation, apk: Path, output: Path, apk_metadata: dict, validation: dict) -> dict:
    """Archive the exact reserved commit; never include local files or overwrite a package."""
    if commit_sha(repo, "HEAD") != record.source_sha or commit_sha(repo, record.tag) != record.source_sha:
        raise ReleaseError("Package checkout, reservation and source tag must be identical")
    if git(repo, "status", "--porcelain", "--untracked-files=no"):
        raise ReleaseError("Refusing to package a modified tracked working tree")
    if output.exists() and any(output.iterdir()):
        raise ReleaseError("Output directory must be empty; existing release artifacts are immutable")
    if apk_metadata.get("version_code") != record.version_code or apk_metadata.get("certificate_sha256") != CERT:
        raise ReleaseError("Verified APK metadata does not match the reservation")
    output.mkdir(parents=True, exist_ok=True)
    apk_name = f"mihon-translator-v{record.number}-arm64-v8a.apk"
    source_name = f"mihon-translator-v{record.number}-source.zip"
    shutil.copyfile(apk, output / apk_name)
    git(repo, "archive", "--format=zip", f"--prefix=mihon-translator-v{record.number}/",
        "--output", str((output / source_name).resolve()), record.source_sha)
    (output / "validation.json").write_text(json.dumps(validation, indent=2, sort_keys=True) + "\n")
    files = [{"name": name, "bytes": (output / name).stat().st_size, "sha256": sha256(output / name)}
             for name in (apk_name, source_name, "validation.json")]
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    manifest = {"schema": 1, "repository": REPOSITORY, "release_branch": RELEASE_BRANCH,
                "tag": record.tag, "number": record.number, "source_sha": record.source_sha,
                "upstream_sha": record.upstream_sha, "version_code": record.version_code,
                "source_commit_at": git(repo, "show", "-s", "--format=%cI", record.source_sha),
                "workflow_run_url": f"https://github.com/{REPOSITORY}/actions/runs/{run_id}" if run_id.isdigit() else None,
                "apk": apk_metadata, "assets": files, "prerelease": True,
                "device_validation": validation["device_validation"],
                "overall_acceptance": "pending", "provider_calls": 0}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    names = [item["name"] for item in files] + ["manifest.json"]
    (output / "SHA256SUMS").write_text("".join(f"{sha256(output / name)}  {name}\n" for name in names))
    return manifest


def check_signing_environment(repo: Path) -> None:
    import base64
    for name in ("storeFileBase64", "storePassword", "keyAlias", "keyPassword"):
        if not os.environ.get(name):
            raise ReleaseError(f"Missing protected-environment secret for {name}; no debug-key fallback is allowed")
    expected = os.environ.get("TRANSLATOR_SIGNING_CERT_SHA256", "").lower().replace(":", "")
    if expected != CERT or os.environ.get("MIHON_GITHUB_RELEASE") != "true":
        raise ReleaseError("Explicit release signing mode and the preserved certificate fingerprint are required")
    try:
        encoded = os.environ["storeFileBase64"]
        if len(encoded) > 2 * 1024 * 1024:
            raise ReleaseError("Signing material exceeds the configured input limit")
        data = base64.b64decode(encoded, validate=True)
    except ValueError as error:
        raise ReleaseError("Signing material is not valid base64") from error
    with tempfile.NamedTemporaryFile() as store:
        store.write(data)
        store.flush()
        result = command(repo, "keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v",
                         "-keystore", store.name, "-storepass:env", "storePassword", "-alias", os.environ["keyAlias"])
    match = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", result.stdout)
    if not match or match[1].lower().replace(":", "") != CERT:
        raise ReleaseError("Imported signing key does not match the preserved installation certificate")


def blocked_sync_marker(source: str, upstream: str) -> str:
    if not SHA.fullmatch(source) or not SHA.fullmatch(upstream):
        raise ReleaseError("Invalid blocked-sync identity")
    key = hashlib.sha256(f"{source}\n{upstream}".encode()).hexdigest()
    return f"<!-- translator-sync-blocked:v1:{key} -->"


def record_blocked_sync(repo: Path, source: str, upstream: str) -> None:
    """Record one actionable issue comment per exact pair, never every polling interval."""
    marker = blocked_sync_marker(source, upstream)
    result = gh(repo, "api", "--paginate", "--slurp", f"repos/{REPOSITORY}/issues/1/comments?per_page=100")
    if len(result.stdout) > 16 * 1024 * 1024:
        raise ReleaseError("Issue history exceeds deduplication limit; no duplicate comment was posted")
    pages = json.loads(result.stdout)
    if any(marker in str(comment.get("body", "")) for page in pages for comment in page):
        return
    body = (f"{marker}\nTranslator upstream synchronization is blocked for source `{source}` and upstream "
            f"`{upstream}`. The automatic merge/fast-forward failed; neither history was rewritten. "
            "Resolve the upstream conflict on the translator branch (or restore a fast-forwardable main mirror), "
            "then push the reviewed resolution. No release was allocated for this failed synchronization.")
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as payload:
        json.dump({"body": body}, payload)
        payload.flush()
        gh(repo, "api", "--method", "POST", f"repos/{REPOSITORY}/issues/1/comments", "--input", payload.name)


def remote_sha(repo: Path, remote: str, ref: str) -> str:
    lines = git(repo, "ls-remote", "--exit-code", remote, ref).splitlines()
    matched = [line.split()[0] for line in lines if len(line.split()) == 2 and line.split()[1] == ref]
    if len(matched) != 1 or not SHA.fullmatch(matched[0]):
        raise ReleaseError("Cannot verify exact remote reference")
    return matched[0]


def remote_heads_current(repo: Path, source: str, upstream: str) -> bool:
    current_source = remote_sha(repo, "origin", f"refs/heads/{RELEASE_BRANCH}")
    if remote_sha(repo, UPSTREAM, "refs/heads/main") != upstream:
        return False
    if current_source == source:
        return True
    if command(repo, "git", "cat-file", "-e", current_source, check=False).returncode:
        git(repo, "fetch", "--no-tags", "origin", current_source)
    return same_release_inputs(repo, source, current_source)


def verify_bundle(repo: Path, record: Reservation, directory: Path) -> dict[str, str]:
    expected = {f"mihon-translator-v{record.number}-arm64-v8a.apk", f"mihon-translator-v{record.number}-source.zip",
                "validation.json", "manifest.json", "SHA256SUMS"}
    actual = {path.name for path in directory.iterdir()}
    if actual != expected or any(not (directory / name).is_file() or (directory / name).is_symlink() for name in actual):
        raise ReleaseError("Release directory contains missing, unexpected or indirect files")
    checksums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        parts = line.split("  ")
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]) or parts[1] in checksums:
            raise ReleaseError("Invalid release checksum manifest")
        checksums[parts[1]] = parts[0]
    if set(checksums) != expected - {"SHA256SUMS"}:
        raise ReleaseError("Release checksum manifest has missing or unexpected assets")
    for name, digest in checksums.items():
        if sha256(directory / name) != digest:
            raise ReleaseError("Release asset checksum mismatch; no files may be replaced")
    manifest = json.loads((directory / "manifest.json").read_text())
    if (manifest.get("schema") != 1 or manifest.get("source_sha") != record.source_sha or manifest.get("upstream_sha") != record.upstream_sha or
            manifest.get("tag") != record.tag or manifest.get("version_code") != record.version_code or
            manifest.get("repository") != REPOSITORY or manifest.get("number") != record.number):
        raise ReleaseError("Release manifest does not match the immutable reservation")
    if commit_sha(repo, record.tag) != record.source_sha:
        raise ReleaseError("Release source tag has changed")
    validation = json.loads((directory / "validation.json").read_text())
    if not isinstance(validation, dict) or validation.get("schema") != 1:
        raise ReleaseError("Unsupported validation summary schema")
    with zipfile.ZipFile(directory / f"mihon-translator-v{record.number}-source.zip") as source:
        if source.comment != record.source_sha.encode("ascii"):
            raise ReleaseError("Source archive is not bound to the reserved Git commit")
    asset_names = [asset["name"] for asset in manifest["assets"]]
    if len(asset_names) != 3 or set(asset_names) != expected - {"manifest.json", "SHA256SUMS"}:
        raise ReleaseError("Manifest asset list is incomplete or contains duplicates")
    for asset in manifest["assets"]:
        name = asset["name"]
        if name not in checksums or asset["sha256"] != checksums[name] or asset["bytes"] != (directory / name).stat().st_size:
            raise ReleaseError("Manifest asset identity does not match release files")
    checksums["SHA256SUMS"] = sha256(directory / "SHA256SUMS")
    return checksums


def assets_to_upload(local: dict[str, str], remote: dict[str, str], published: bool) -> list[str]:
    if set(remote) - set(local):
        raise ReleaseError("Release contains unexpected immutable assets")
    if any(local[name] != digest for name, digest in remote.items()):
        raise ReleaseError("Existing release assets are immutable and differ from this package")
    missing = sorted(set(local) - set(remote))
    if published and missing:
        raise ReleaseError("A published release cannot receive missing/replacement assets")
    return missing


def select_original_bundle(record: Reservation, artifacts: list[dict], draft_has_assets: bool) -> dict | None:
    """Select the first preserved bundle, never a later rebuilt replacement."""
    pattern = re.compile(re.escape(record.tag) + r"-([1-9][0-9]*)-bundle\Z")
    candidates = [item for item in artifacts if isinstance(item, dict) and
                  isinstance(item.get("name"), str) and pattern.fullmatch(item["name"])]
    if not candidates:
        if draft_has_assets:
            raise ReleaseError("Draft assets exist but their original preserved bundle is unavailable; refusing to rebuild")
        return None
    if any(type(item.get("id")) is not int or item["id"] <= 0 for item in candidates):
        raise ReleaseError("Original bundle artifact has an invalid identity")
    artifact = min(candidates, key=lambda item: item["id"])
    match = pattern.fullmatch(artifact["name"])
    run = artifact.get("workflow_run", {})
    if (artifact.get("expired") is not False or not isinstance(run, dict) or
            type(run.get("id")) is not int or run["id"] != int(match[1]) or
            run.get("head_branch") != RELEASE_BRANCH or
            not re.fullmatch(r"sha256:[0-9a-f]{64}", str(artifact.get("digest", "")))):
        raise ReleaseError("Original bundle is expired or its digest/run identity cannot be verified; refusing to rebuild")
    return artifact


def original_bundle(repo: Path, record: Reservation) -> dict | None:
    response = gh(repo, "api", "--paginate", "--slurp", f"repos/{REPOSITORY}/actions/artifacts?per_page=100")
    if len(response.stdout) > 16 * 1024 * 1024:
        raise ReleaseError("Artifact history exceeds the identity lookup limit; refusing to rebuild")
    pages = json.loads(response.stdout)
    artifacts = [item for page in pages for item in page.get("artifacts", [])]
    current = release_metadata(repo, record.tag)
    return select_original_bundle(record, artifacts, bool(current and current.get("assets")))


def verify_artifact_run(repo: Path, artifact: dict) -> None:
    run_id = artifact["workflow_run"]["id"]
    run = json.loads(gh(repo, "api", f"repos/{REPOSITORY}/actions/runs/{run_id}").stdout)
    if (run.get("id") != run_id or run.get("repository", {}).get("full_name") != REPOSITORY or
            run.get("head_repository", {}).get("full_name") != REPOSITORY or
            run.get("head_branch") != RELEASE_BRANCH or
            run.get("event") not in {"push", "schedule", "workflow_dispatch"} or
            run.get("path") != ".github/workflows/translator-release.yml"):
        raise ReleaseError("Preserved bundle was not produced by this fork's translator release workflow")


def extract_original_bundle(repo: Path, record: Reservation, archive: Path, output: Path, artifact: dict) -> None:
    checked = select_original_bundle(record, [artifact], True)
    if sha256(archive) != checked["digest"].removeprefix("sha256:"):
        raise ReleaseError("Preserved artifact archive digest does not match GitHub's immutable identity")
    if output.exists() and any(output.iterdir()):
        raise ReleaseError("Resume output must be empty; release bytes are immutable")
    expected = {f"mihon-translator-v{record.number}-arm64-v8a.apk", f"mihon-translator-v{record.number}-source.zip",
                "manifest.json", "validation.json", "SHA256SUMS"}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="translator-resume-", dir=output.parent) as temporary:
        staged = Path(temporary)
        with zipfile.ZipFile(archive) as compressed:
            members = compressed.infolist()
            if (len(members) != len(expected) or {entry.filename for entry in members} != expected or
                    sum(entry.file_size for entry in members) > 2 * 1024 ** 3 or
                    any(entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16) for entry in members)):
                raise ReleaseError("Preserved bundle has unsafe, duplicate, missing or oversized members")
            for entry in members:
                with compressed.open(entry) as source, (staged / entry.filename).open("wb") as target:
                    shutil.copyfileobj(source, target, 1024 * 1024)
        verify_bundle(repo, record, staged)
        manifest = json.loads((staged / "manifest.json").read_text())
        expected_run = f"https://github.com/{REPOSITORY}/actions/runs/{artifact['workflow_run']['id']}"
        if manifest.get("workflow_run_url") != expected_run:
            raise ReleaseError("Preserved bundle manifest belongs to a different workflow run")
        if output.exists():
            output.rmdir()
        # All identities verified before making the restored files visible.
        staged.rename(output)


def restore_original_bundle(repo: Path, record: Reservation, artifact_id: int, output: Path) -> None:
    if artifact_id <= 0:
        raise ReleaseError("Invalid preserved artifact ID")
    original = original_bundle(repo, record)
    if original is None or original["id"] != artifact_id:
        raise ReleaseError("Requested artifact is not this reservation's original preserved bundle")
    verify_artifact_run(repo, original)
    if type(original.get("size_in_bytes")) is not int or not 0 < original["size_in_bytes"] <= 2 * 1024 ** 3:
        raise ReleaseError("Preserved bundle archive exceeds the download limit")
    with tempfile.TemporaryDirectory() as temporary:
        archive = Path(temporary) / "bundle.zip"
        with archive.open("wb") as destination:
            result = subprocess.run(
                ["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip"], cwd=repo,
                stdout=destination, stderr=subprocess.PIPE,
            )
        if result.returncode:
            raise ReleaseError("Could not download the original preserved bundle")
        if archive.stat().st_size > 2 * 1024 ** 3:
            raise ReleaseError("Preserved bundle download exceeds the archive limit")
        extract_original_bundle(repo, record, archive, output, original)


def release_description(record: Reservation, validation: dict) -> tuple[str, str]:
    device = validation.get("device_validation", {})
    runs = device.get("runs", []) if isinstance(device, dict) else []
    observed_host = any(validation.get(key, {}).get("status") in {"passed", "failed"}
                        for key in ("formatting", "sqldelight_migrations", "unit_tests"))
    observed_device = any(run.get("status") in {"passed", "failed"} for run in runs)
    label = "Partially tested" if observed_host or observed_device else "Untested"
    title = f"Mihon Translator v{record.number} — {label}"
    lines = [f"**{label}. Overall acceptance and human passage approval remain pending.**",
             f"Normal minified ARM64 release from `{record.source_sha}` containing upstream `{record.upstream_sha}`.",
             f"Version code: {record.version_code}. Signed with the preserved installation certificate."]
    for key, name in (("formatting", "Formatting"), ("sqldelight_migrations", "SQLDelight migrations"),
                      ("unit_tests", "Host unit tests")):
        status = validation.get(key, {}).get("status", "not_run")
        status = status if status in {"passed", "failed", "not_run"} else "unavailable"
        lines.append(f"{name}: {status.replace('_', ' ')}.")
    if runs:
        lines.append(f"Released app.mihon artifact SHA-256: `{device['apk_sha256']}`. "
                     "The focused native results below identify the actual package and APKs used for each run. "
                     "app.mihon.benchmark entries cover the minified benchmark companion; "
                     "app.mihon entries cover the released application.")
        for run in runs:
            name = "K90" if run.get("kind") == "k90" else "16 KB emulator"
            lines.append(f"- {name}: {run['status']}; {run['tests']} tests, {run['failures']} failures, "
                         f"{run['errors']} errors, {run['skipped']} skipped; kernel page size {run['page_size_bytes']} bytes. "
                         f"Tested package: `{run['tested_package']}`. Tested APK SHA-256: `{run['tested_apk_sha256']}`. "
                         f"Instrumentation APK SHA-256: `{run['instrumentation_apk_sha256']}`.")
        lines.append("These focused results do not establish full visual, performance or human meaning acceptance.")
    else:
        lines.append("No hash-bound device test results are recorded for this artifact.")
    lines.append("See validation.json for the recorded scope and manifest.json/SHA256SUMS for source and artifact identities. "
                 "Release tooling makes no translation-provider requests; historical provider validation has its own scope.")
    return title, "\n\n".join(lines)


def publish(repo: Path, record: Reservation, directory: Path) -> str:
    require_fork_context()
    local = verify_bundle(repo, record, directory)
    if remote_sha(repo, "origin", f"refs/tags/{record.tag}^{{}}") != record.source_sha:
        raise ReleaseError("Remote release tag differs from the reserved source")
    if remote_sha(repo, "origin", f"refs/tags/{record.tag}") != git(repo, "rev-parse", f"refs/tags/{record.tag}"):
        raise ReleaseError("Remote annotated reservation differs from the local reservation")
    current = release_metadata(repo, record.tag)
    already_published = current is not None and not current.get("draft", False)
    if not already_published and not remote_heads_current(repo, record.source_sha, record.upstream_sha):
        return "deferred_source_advanced"
    validation = json.loads((directory / "validation.json").read_text())
    title, notes = release_description(record, validation)
    if current is None:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as body:
            body.write(notes)
            body.flush()
            gh(repo, "release", "create", record.tag, "--repo", REPOSITORY, "--verify-tag", "--draft", "--prerelease",
               "--title", title, "--notes-file", body.name)
        current = release_metadata(repo, record.tag)
    if not current or not current.get("prerelease", False):
        raise ReleaseError("Existing release is not the expected prerelease")
    remote = {}
    for asset in current.get("assets", []):
        name = asset.get("name")
        if name not in local or name in remote:
            raise ReleaseError("Existing release has unexpected/duplicate assets")
        digest = asset.get("digest", "") or ""
        if re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            remote[name] = digest.removeprefix("sha256:")
        else:
            # Older assets may lack GitHub's digest. Download exactly this owned asset for verification.
            with tempfile.TemporaryDirectory() as temp:
                destination = Path(temp) / name
                gh(repo, "release", "download", record.tag, "--repo", REPOSITORY, "--pattern", name,
                   "--output", str(destination))
                remote[name] = sha256(destination)
    missing = assets_to_upload(local, remote, already_published)
    for name in missing:
        gh(repo, "release", "upload", record.tag, str((directory / name).resolve()), "--repo", REPOSITORY)
    if already_published:
        return "already_published"
    if not remote_heads_current(repo, record.source_sha, record.upstream_sha):
        return "deferred_source_advanced"
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as body:
        body.write(notes)
        body.flush()
        gh(repo, "release", "edit", record.tag, "--repo", REPOSITORY, "--draft=false", "--prerelease", "--latest=false",
           "--title", title, "--notes-file", body.name)
    return "published"


def find_android_tool(name: str, explicit: str | None) -> str:
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise ReleaseError(f"Configured Android {name} executable does not exist")
        return str(path.resolve())
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = list((Path(sdk) / "build-tools").glob(f"*/{name}"))
        candidates.sort(key=lambda path: tuple(int(value) for value in re.findall(r"\d+", path.parent.name)), reverse=True)
        if candidates:
            return str(candidates[0].resolve())
    path = shutil.which(name)
    if path:
        return path
    raise ReleaseError(f"Android {name} was not found; set ANDROID_HOME or pass its executable path")


def record_by_tag(repo: Path, tag: str) -> Reservation:
    record = next((item for item in reservations(repo) if item.tag == tag), None)
    if record is None:
        raise ReleaseError("No immutable reservation exists for the requested tag")
    return record


def emit(record: Reservation, **extra) -> None:
    values = {**dataclasses.asdict(record), "tag": record.tag, "version_code": record.version_code, **extra}
    print(json.dumps(values, sort_keys=True))
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            for name, value in values.items():
                text = str(value).lower() if isinstance(value, bool) else str(value)
                if "\n" in text or "\r" in text:
                    raise ReleaseError("Unexpected multiline workflow output")
                stream.write(f"{name}={text}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    commands = parser.add_subparsers(dest="command", required=True)
    reserve = commands.add_parser("reserve", help="Reserve a local version tag without remote writes")
    reserve.add_argument("--source", default="HEAD")
    reserve.add_argument("--upstream", required=True)
    reserve.add_argument("--number", type=int)
    sync = commands.add_parser("sync", help="CI-only fast-forward mirror, merge and immutable tag reservation")
    sync.add_argument("--retry-reserved", action="store_true")
    restore = commands.add_parser("restore-bundle", help="Restore the original preserved bundle without rebuilding")
    restore.add_argument("--tag", required=True)
    restore.add_argument("--artifact-id", type=int, required=True)
    restore.add_argument("--output", type=Path, required=True)
    commands.add_parser("check-signing", help="Verify the protected signing environment before Gradle")
    package = commands.add_parser("package", help="Verify and package an existing exact-source ARM64 APK")
    package.add_argument("--tag", required=True)
    package.add_argument("--apk", type=Path, required=True)
    package.add_argument("--output", type=Path, required=True)
    package.add_argument("--apksigner")
    package.add_argument("--aapt")
    package.add_argument("--zipalign")
    package.add_argument("--device-results", type=Path)
    package.add_argument("--formatting", choices=("passed", "failed", "not_run"), default="not_run")
    package.add_argument("--migrations", choices=("passed", "failed", "not_run"), default="not_run")
    package.add_argument("--unit-status", choices=("passed", "failed", "not_run"), default="not_run")
    package.add_argument("--unit-results", type=Path, action="append", default=[])
    publish_parser = commands.add_parser("publish", help="Publish/retry the same bundle without replacing any assets")
    publish_parser.add_argument("--tag", required=True)
    publish_parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    try:
        if args.command == "reserve":
            emit(reserve_local(repo, args.source, args.upstream, args.number))
        elif args.command == "sync":
            record, needed = sync_and_reserve(repo, args.retry_reserved)
            artifact = original_bundle(repo, record) if needed and args.retry_reserved else None
            emit(record, build=needed, bundle_artifact_id=artifact["id"] if artifact else "")
        elif args.command == "restore-bundle":
            record = record_by_tag(repo, args.tag)
            restore_original_bundle(repo, record, args.artifact_id, args.output.resolve())
            emit(record, restored=True)
        elif args.command == "check-signing":
            check_signing_environment(repo)
            print("Preserved signing certificate verified")
        elif args.command == "package":
            record = record_by_tag(repo, args.tag)
            apk = args.apk.resolve()
            signer = command(repo, find_android_tool("apksigner", args.apksigner), "verify", "--print-certs", str(apk)).stdout
            manifest = command(repo, find_android_tool("aapt", args.aapt), "dump", "badging", str(apk)).stdout
            metadata = verify_apk_contract(apk, record, signer, manifest)
            command(repo, find_android_tool("zipalign", args.zipalign), "-c", "-P", "16", "-v", "4", str(apk))
            metadata["zip_alignment_16kb"] = "passed"
            reports = []
            for path in args.unit_results:
                if path.is_file():
                    reports.append(path)
                elif path.is_dir():
                    reports.extend(path.rglob("TEST-*.xml"))
                else:
                    raise ReleaseError("A unit-result input does not exist")
            validation = validation_summary(reports, args.formatting, args.migrations, args.unit_status,
                                            dt.datetime.now(dt.timezone.utc).isoformat())
            if args.device_results:
                if args.device_results.stat().st_size > 64 * 1024:
                    raise ReleaseError("Device receipt exceeds 64 KiB")
                validation["device_validation"] = device_summary(
                    json.loads(args.device_results.read_text()), record, sha256(apk))
            bundle(repo, record, apk, args.output.resolve(), metadata, validation)
            verify_bundle(repo, record, args.output.resolve())
            emit(record, packaged=True)
        else:
            record = record_by_tag(repo, args.tag)
            emit(record, publication=publish(repo, record, args.directory.resolve()))
        return 0
    except (ReleaseError, OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as error:
        # Our errors are fixed/sanitized messages; do not expose arbitrary HTTP bodies or subprocess stderr.
        if isinstance(error, ReleaseError):
            print(f"Release stopped: {error}", file=sys.stderr)
        else:
            print(f"Release stopped: invalid local input ({type(error).__name__})", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
