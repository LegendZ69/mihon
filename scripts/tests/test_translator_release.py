"""Public release-contract tests using isolated Git repositories; no network or providers."""
import importlib.util
import json
import subprocess
import sys
import tempfile
import zipfile
import unittest
from unittest.mock import patch
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "translator_release.py"
SPEC = importlib.util.spec_from_file_location("translator_release", SCRIPT)
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


class ReleaseMetadataTests(unittest.TestCase):
    def test_tag_404_discovers_exact_draft_on_later_authenticated_release_page(self):
        draft = {"id": 15, "tag_name": "translator-v15", "draft": True, "prerelease": True, "assets": []}
        other = {**draft, "id": 16, "tag_name": "translator-v150"}
        missing = subprocess.CompletedProcess([], 1, "", "gh: Not Found (HTTP 404)")
        listing = subprocess.CompletedProcess([], 0, json.dumps([[other], [draft]]), "")
        with patch.object(release, "gh", side_effect=[missing, listing]) as client:
            self.assertEqual(draft, release.release_metadata(Path("."), "translator-v15"))
        self.assertEqual(2, client.call_count)
        self.assertIn("--paginate", client.call_args.args)
        self.assertIn("--slurp", client.call_args.args)


    def test_release_discovery_only_returns_absent_after_successful_complete_listing(self):
        missing = subprocess.CompletedProcess([], 1, "", "gh: Not Found (HTTP 404)")
        other = {"id": 150, "tag_name": "translator-v150", "draft": True, "prerelease": True, "assets": []}
        for pages in ([[]], [[other], []]):
            with self.subTest(pages=pages), patch.object(release, "gh", side_effect=[
                missing, subprocess.CompletedProcess([], 0, json.dumps(pages), "")
            ]):
                self.assertIsNone(release.release_metadata(Path("."), "translator-v15"))

    def test_published_tag_uses_direct_lookup_and_rejects_wrong_or_invalid_metadata(self):
        published = {"id": 15, "tag_name": "translator-v15", "draft": False, "prerelease": True, "assets": []}
        with patch.object(release, "gh", return_value=subprocess.CompletedProcess([], 0, json.dumps(published), "")) as client:
            self.assertEqual(published, release.release_metadata(Path("."), "translator-v15"))
        self.assertEqual(1, client.call_count)
        for body in ("null", "[]", "{", json.dumps({**published, "tag_name": "translator-v16"}),
                     json.dumps({**published, "draft": "false"})):
            with self.subTest(body=body), patch.object(release, "gh", return_value=
                    subprocess.CompletedProcess([], 0, body, "")):
                with self.assertRaises(release.ReleaseError):
                    release.release_metadata(Path("."), "translator-v15")

    def test_release_discovery_refuses_authentication_transport_and_pagination_errors(self):
        missing = subprocess.CompletedProcess([], 1, "", "gh: Not Found (HTTP 404)")
        for status in (401, 403, 500):
            failed = subprocess.CompletedProcess([], 1, "", f"gh: failure (HTTP {status})")
            with self.subTest(tag_status=status), patch.object(release, "gh", return_value=failed) as client:
                with self.assertRaises(release.ReleaseError):
                    release.release_metadata(Path("."), "translator-v15")
                self.assertEqual(1, client.call_count)
        for status in (401, 403, 404, 502):
            # Even a partial successful page cannot establish draft absence when pagination fails.
            failed = subprocess.CompletedProcess([], 1, "[[]]", f"gh: failure (HTTP {status})")
            with self.subTest(list_status=status), patch.object(release, "gh", side_effect=[missing, failed]):
                with self.assertRaises(release.ReleaseError):
                    release.release_metadata(Path("."), "translator-v15")

    def test_release_listing_rejects_malformed_pages_duplicate_tags_and_invalid_flags(self):
        missing = subprocess.CompletedProcess([], 1, "", "gh: Not Found (HTTP 404)")
        draft = {"id": 15, "tag_name": "translator-v15", "draft": True, "prerelease": True, "assets": []}
        invalid = ["{", "null", "{}", "[]", json.dumps([draft]), json.dumps([[None]]),
                   json.dumps([[draft], [draft]]), json.dumps([[draft], [{**draft, "id": 16}]])]
        for change in ({"id": True}, {"id": 0}, {"tag_name": None}, {"draft": 1},
                       {"prerelease": None}, {"assets": {}}, {"assets": [None]}):
            invalid.append(json.dumps([[{**draft, **change}]]))
        invalid.append(json.dumps([[{key: value for key, value in draft.items() if key != "draft"}]]))
        for body in invalid:
            with self.subTest(body=body), patch.object(release, "gh", side_effect=[
                missing, subprocess.CompletedProcess([], 0, body, "")
            ]):
                with self.assertRaises(release.ReleaseError):
                    release.release_metadata(Path("."), "translator-v15")


class ReservationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        git(self.repo, "init", "-q", "--initial-branch=codex/manga-translator")
        git(self.repo, "config", "user.email", "fixture@example.invalid")
        git(self.repo, "config", "user.name", "Release fixture")
        (self.repo / "source.txt").write_text("original\n")
        git(self.repo, "add", "source.txt")
        git(self.repo, "commit", "-qm", "fixture original")
        self.upstream = git(self.repo, "rev-parse", "HEAD")

    def commit(self, text):
        (self.repo / "source.txt").write_text(text)
        git(self.repo, "commit", "-qam", "fixture change")
        return git(self.repo, "rev-parse", "HEAD")

    def test_first_reservation_is_v15_and_rerun_reuses_exact_source_tag(self):
        first = release.reserve_local(self.repo, "HEAD", self.upstream)
        self.assertEqual("translator-v15", first.tag)
        self.assertEqual(100015, first.version_code)
        self.assertEqual(self.upstream, first.source_sha)
        tag_object = git(self.repo, "rev-parse", first.tag)
        rerun = release.reserve_local(self.repo, "HEAD", self.upstream)
        self.assertEqual(first, rerun)
        self.assertEqual(tag_object, git(self.repo, "rev-parse", first.tag))
        next_source = self.commit("new source\n")
        next_release = release.reserve_local(self.repo, next_source, self.upstream)
        self.assertEqual("translator-v16", next_release.tag)
        self.assertEqual(100016, next_release.version_code)
        self.assertEqual(next_source, git(self.repo, "rev-parse", "translator-v16^{commit}"))


    def test_reservation_never_reuses_a_number_or_accepts_moved_lightweight_tags(self):
        first = release.reserve_local(self.repo, "HEAD", self.upstream)
        self.commit("next\n")
        with self.assertRaises(release.ReleaseError):
            release.reserve_local(self.repo, "HEAD", self.upstream, number=15)
        git(self.repo, "tag", "-d", first.tag)
        git(self.repo, "tag", first.tag)
        with self.assertRaisesRegex(release.ReleaseError, "annotated"):
            release.reserve_local(self.repo, "HEAD", self.upstream)

    def test_merge_preserves_translator_changes_and_aborts_conflicts_without_rewriting(self):
        git(self.repo, "branch", "fixture-upstream")
        (self.repo / "translation.txt").write_text("preserve translator\n")
        git(self.repo, "add", "translation.txt")
        git(self.repo, "commit", "-qm", "translator feature")
        translation = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "checkout", "-q", "fixture-upstream")
        upstream = self.commit("upstream revision\n")
        merged = release.merge_upstream(self.repo, release.RELEASE_BRANCH, upstream)
        self.assertEqual("preserve translator\n", (self.repo / "translation.txt").read_text())
        self.assertEqual("upstream revision\n", (self.repo / "source.txt").read_text())
        self.assertEqual("", git(self.repo, "status", "--porcelain"))
        self.assertEqual(translation, git(self.repo, "rev-parse", merged + "^1"))
        self.assertEqual(upstream, git(self.repo, "rev-parse", merged + "^2"))
        local = self.commit("local conflict\n")
        git(self.repo, "checkout", "-q", "fixture-upstream")
        conflict = self.commit("upstream conflict\n")
        with self.assertRaisesRegex(release.ReleaseError, "conflict"):
            release.merge_upstream(self.repo, release.RELEASE_BRANCH, conflict)
        self.assertEqual(local, git(self.repo, "rev-parse", release.RELEASE_BRANCH))
        self.assertEqual("local conflict\n", (self.repo / "source.txt").read_text())
        self.assertEqual("", git(self.repo, "status", "--porcelain"))


    def test_unchanged_published_or_failed_source_is_noop_until_explicit_retry(self):
        reservation = release.reserve_local(self.repo, "HEAD", self.upstream)
        self.assertTrue(release.build_needed(None, False, False))
        self.assertFalse(release.build_needed(reservation, False, False))
        self.assertTrue(release.build_needed(reservation, False, True))
        self.assertFalse(release.build_needed(reservation, True, True))

    def test_missing_signing_configuration_stops_before_keytool_or_gradle(self):
        valid = {
            "storeFileBase64": "c3ludGhldGlj",
            "storePassword": "fixture-store-password",
            "keyAlias": "fixture-alias",
            "keyPassword": "fixture-key-password",
            "TRANSLATOR_SIGNING_CERT_SHA256": release.CERT,
            "MIHON_GITHUB_RELEASE": "true",
        }
        for name in valid:
            with self.subTest(missing=name):
                environment = {key: value for key, value in valid.items() if key != name}
                with patch.dict(release.os.environ, environment, clear=True), patch.object(release, "command") as runner:
                    with self.assertRaises(release.ReleaseError):
                        release.check_signing_environment(self.repo)
                    runner.assert_not_called()
        for change in (
            {"TRANSLATOR_SIGNING_CERT_SHA256": "0" * 64},
            {"MIHON_GITHUB_RELEASE": "false"},
            {"storeFileBase64": "not-valid-base64!"},
        ):
            with self.subTest(invalid=list(change)):
                with patch.dict(release.os.environ, {**valid, **change}, clear=True):
                    with patch.object(release, "command") as runner:
                        with self.assertRaises(release.ReleaseError):
                            release.check_signing_environment(self.repo)
                        runner.assert_not_called()

    def test_concurrent_tag_reservations_cannot_overwrite_the_first_remote_identity(self):
        with tempfile.TemporaryDirectory() as remote_temp, tempfile.TemporaryDirectory() as clone_temp:
            remote = Path(remote_temp) / "remote.git"
            second = Path(clone_temp) / "second"
            git(self.repo, "init", "-q", "--bare", str(remote))
            git(self.repo, "remote", "add", "origin", str(remote))
            git(self.repo, "push", "-q", "origin", f"HEAD:refs/heads/{release.RELEASE_BRANCH}")
            git(self.repo, "clone", "-q", "--branch", release.RELEASE_BRANCH, str(remote), str(second))
            git(second, "config", "user.email", "fixture@example.invalid")
            git(second, "config", "user.name", "Concurrent release fixture")
            first_source = self.commit("first contender\n")
            (second / "source.txt").write_text("second contender\n")
            git(second, "commit", "-qam", "second contender")
            first = release.reserve_local(self.repo, "HEAD", self.upstream)
            other = release.reserve_local(second, "HEAD", self.upstream)
            self.assertEqual(first.number, other.number)
            self.assertNotEqual(first.source_sha, other.source_sha)
            refspec = f"refs/tags/{first.tag}:refs/tags/{first.tag}"
            release.git(self.repo, "push", "origin", refspec)
            first_tag_object = git(self.repo, "rev-parse", first.tag)
            with self.assertRaises(release.ReleaseError):
                release.git(second, "push", "origin", refspec)
            self.assertEqual(first_source, git(remote, "rev-parse", f"{first.tag}^{{commit}}"))
            self.assertEqual(first_tag_object, git(remote, "rev-parse", first.tag))
            self.assertEqual(first.tag, git(remote, "tag", "--list", "translator-v*"))

    def test_stale_main_mirror_fetch_cannot_overwrite_a_concurrent_remote_advance(self):
        with tempfile.TemporaryDirectory() as remote_temp:
            remote = Path(remote_temp) / "remote.git"
            git(self.repo, "init", "-q", "--bare", str(remote))
            git(self.repo, "remote", "add", "origin", str(remote))
            git(self.repo, "push", "-q", "origin", "HEAD:refs/heads/main")
            git(self.repo, "fetch", "-q", "origin", "refs/heads/main:refs/remotes/origin/main")
            candidate = self.commit("candidate upstream\n")
            concurrent = self.commit("concurrent mirror advance\n")
            git(self.repo, "push", "-q", "origin", f"{concurrent}:refs/heads/main")
            # Reproduce the mirror's snapshot before another writer advanced the remote.
            git(self.repo, "update-ref", "refs/remotes/origin/main", self.upstream)
            with self.assertRaises(release.ReleaseError):
                release.mirror_main(self.repo, candidate)
            self.assertEqual(concurrent, git(remote, "rev-parse", "refs/heads/main"))
            self.assertEqual(concurrent, git(self.repo, "rev-parse", "HEAD"))

    def test_apk_contract_rejects_wrong_cert_version_package_debug_or_abi(self):
        reservation = release.reserve_local(self.repo, "HEAD", self.upstream)
        apk = self.repo / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libfixture.so", b"fixture")
        manifest = "package: name='app.mihon' versionCode='100015' versionName='0.20.4-translator.15'"
        signer = "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + release.CERT
        checked = release.verify_apk_contract(apk, reservation, signer, manifest)
        self.assertEqual("0.20.4-translator.15", checked["version_name"])
        for bad in (
            manifest.replace("100015", "29"),
            manifest.replace("app.mihon", "app.mihon.benchmark"),
            manifest.replace("translator.15", "translator.14"),
            manifest + "\napplication-debuggable",
        ):
            with self.subTest(manifest=bad), self.assertRaises(release.ReleaseError):
                release.verify_apk_contract(apk, reservation, signer, bad)
        with self.assertRaises(release.ReleaseError):
            release.verify_apk_contract(apk, reservation, signer.replace(release.CERT, "0" * 64), manifest)
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("lib/x86_64/libfixture.so", b"fixture")
        with self.assertRaises(release.ReleaseError):
            release.verify_apk_contract(apk, reservation, signer, manifest)

    def test_apk_contract_accepts_sdk37_v2_signer_for_preserved_certificate(self):
        reservation = release.reserve_local(self.repo, "HEAD", self.upstream)
        apk = self.repo / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libfixture.so", b"fixture")
        manifest = "package: name='app.mihon' versionCode='100015' versionName='0.20.4-translator.15'"
        signer = ("Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
                  "Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + release.CERT)
        checked = release.verify_apk_contract(apk, reservation, signer, manifest)
        self.assertEqual(release.CERT, checked["certificate_sha256"])

    def test_apk_contract_accepts_known_scheme_reports_only_with_one_preserved_identity(self):
        reservation = release.reserve_local(self.repo, "HEAD", self.upstream)
        apk = self.repo / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libfixture.so", b"fixture")
        manifest = "package: name='app.mihon' versionCode='100015' versionName='0.20.4-translator.15'"
        labels = ["Signer #1", "V1 Signer:", "V2 Signer:", "V3.0 Signer:",
                  "Signer (minSdkVersion=28, maxSdkVersion=32)",
                  "Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)",
                  "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647)"]
        reports = [f"{label} certificate SHA-256 digest: {release.CERT}" for label in labels]
        for lines in ([line] for line in reports):
            with self.subTest(lines=lines):
                checked = release.verify_apk_contract(apk, reservation,
                    "Number of signers: 1\n" + "\n".join(lines), manifest)
                self.assertEqual(release.CERT, checked["certificate_sha256"])
        # SDK37 can report the same installed signer for more than one signature scheme.
        report = "Number of signers: 1\n" + "\n".join(reports[1:4])
        report += "\nSource Stamp Signer certificate SHA-256 digest: " + "0" * 64
        self.assertEqual(release.CERT, release.verify_apk_contract(apk, reservation,
                         report, manifest)["certificate_sha256"])
        self.assertEqual(release.CERT, release.verify_apk_contract(apk, reservation,
                         report.replace("Source Stamp Signer certificate", "Source Stamp Signer: certificate"),
                         manifest)["certificate_sha256"])

    def test_apk_contract_rejects_ambiguous_malformed_or_untrusted_signer_reports(self):
        reservation = release.reserve_local(self.repo, "HEAD", self.upstream)
        apk = self.repo / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libfixture.so", b"fixture")
        manifest = "package: name='app.mihon' versionCode='100015' versionName='0.20.4-translator.15'"
        certificate = "V2 Signer: certificate SHA-256 digest: " + release.CERT
        valid = "Number of signers: 1\n" + certificate
        invalid = [certificate, valid.replace("signers: 1", "signers: 2"),
                   valid.replace("signers: 1", "signers: 01"), valid.replace("signers: 1", "signers: x"),
                   valid + "\nNumber of signers: 1", valid.replace(release.CERT, "0" * 64),
                   valid.replace("V2 Signer:", "Unknown Signer:"),
                   valid.replace("V2 Signer:", "Signer #2"),
                   valid.replace("V2 Signer:", "Source Stamp Signer"),
                   valid.replace("V2 Signer:", "Source Stamp Signer:"),
                   valid.replace("certificate SHA-256", "public key SHA-256"),
                   valid.replace(release.CERT, release.CERT[:-1]), valid + " extra",
                   valid + "\nSigner #2 certificate SHA-256 digest: " + release.CERT,
                   valid + "\nV1 Signer: certificate SHA-256 digest: " + "0" * 64,
                   valid + "\nV1 Signer: certificate SHA-256 digest: malformed"]
        for label in ("V3.1 Signer: (minSdkVersion=34, maxSdkVersion=33)",
                      "V3.1 Signer: (minSdkVersion=0, maxSdkVersion=33)",
                      "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483648)",
                      "V3.1 Signer: (minSdkVersion=x, maxSdkVersion=33)",
                      "V3.1 Signer: (minSdkVersion=33 (dev release=false), maxSdkVersion=34)"):
            invalid.append(valid + f"\n{label} certificate SHA-256 digest: {release.CERT}")
        for report in invalid:
            with self.subTest(report=report), self.assertRaises(release.ReleaseError):
                release.verify_apk_contract(apk, reservation, report, manifest)

    def test_validation_summary_excludes_raw_failure_text_and_property_secrets(self):
        report = self.repo / "TEST-secret.xml"
        report.write_text('<testsuite tests="3" failures="1" errors="0" skipped="1">'
                          '<properties><property name="api_key" value="never-export-secret"/></properties>'
                          '<testcase name="secret-test"><failure>Bearer private-token</failure></testcase>'
                          '</testsuite>')
        result = release.validation_summary([report], "passed", "passed", "failed", "2026-09-19T00:00:00Z")
        self.assertEqual({"tests": 3, "failures": 1, "errors": 0, "skipped": 1, "reports": 1}, result["unit_tests"]["totals"])
        self.assertEqual("not_run", result["device_validation"]["status"])
        serialized = json.dumps(result)
        self.assertNotIn("never-export-secret", serialized)
        self.assertNotIn("private-token", serialized)
        self.assertNotIn("secret-test", serialized)
        self.assertNotIn(str(self.repo), serialized)


    def test_main_mirror_divergence_is_rejected_without_changing_remote_history(self):
        remote = self.repo / "remote.git"
        git(self.repo, "init", "-q", "--bare", str(remote))
        git(self.repo, "remote", "add", "origin", str(remote))
        git(self.repo, "push", "-q", "origin", "HEAD:refs/heads/main")
        git(self.repo, "fetch", "-q", "origin", "refs/heads/main:refs/remotes/origin/main")
        upstream = self.commit("upstream advance\n")
        release.mirror_main(self.repo, upstream)
        self.assertIn(upstream, git(self.repo, "ls-remote", "--heads", "origin", "main"))
        git(self.repo, "fetch", "-q", "origin", "refs/heads/main:refs/remotes/origin/main")
        git(self.repo, "checkout", "-q", "--detach", self.upstream)
        divergent = self.commit("divergent upstream\n")
        with self.assertRaisesRegex(release.ReleaseError, "diverged"):
            release.mirror_main(self.repo, divergent)
        self.assertIn(upstream, git(self.repo, "ls-remote", "--heads", "origin", "main"))


    def test_bundle_archives_only_reserved_source_and_detects_asset_replacement(self):
        record = release.reserve_local(self.repo, "HEAD", self.upstream)
        apk = self.repo / "fixture.apk"
        apk.write_bytes(b"already-verified-apk-fixture")
        (self.repo / "untracked-secret.txt").write_text("never archive local secrets")
        metadata = {"version_code": 100015, "certificate_sha256": release.CERT}
        validation = release.validation_summary([], "passed", "passed", "passed", "2026-09-20T00:00:00Z")
        output = self.repo / "artifacts"
        release.bundle(self.repo, record, apk, output, metadata, validation)
        checked = release.verify_bundle(self.repo, record, output)
        self.assertEqual(5, len(checked))
        with zipfile.ZipFile(output / "mihon-translator-v15-source.zip") as archive:
            self.assertEqual(["mihon-translator-v15/", "mihon-translator-v15/source.txt"], archive.namelist())
            self.assertEqual(b"original\n", archive.read("mihon-translator-v15/source.txt"))
        with self.assertRaisesRegex(release.ReleaseError, "immutable"):
            release.bundle(self.repo, record, apk, output, metadata, validation)
        (output / "mihon-translator-v15-arm64-v8a.apk").write_bytes(b"replacement")
        with self.assertRaisesRegex(release.ReleaseError, "checksum"):
            release.verify_bundle(self.repo, record, output)

    def test_asset_retries_skip_identical_and_never_overwrite_published_or_partial_assets(self):
        files = {"app.apk": "a" * 64, "manifest.json": "b" * 64}
        self.assertEqual(["manifest.json"], release.assets_to_upload(files, {"app.apk": "a" * 64}, False))
        self.assertEqual([], release.assets_to_upload(files, files, True))
        with self.assertRaisesRegex(release.ReleaseError, "immutable"):
            release.assets_to_upload(files, {"app.apk": "c" * 64}, False)
        with self.assertRaisesRegex(release.ReleaseError, "published"):
            release.assets_to_upload(files, {"app.apk": "a" * 64}, True)
        with self.assertRaises(release.ReleaseError):
            release.assets_to_upload(files, {**files, "unexpected.txt": "d" * 64}, False)

    def test_publish_resumes_hidden_draft_without_recreating_or_replacing_exact_existing_assets(self):
        record = release.reserve_local(self.repo, "HEAD", self.upstream)
        apk = self.repo / "fixture.apk"
        apk.write_bytes(b"verified-synthetic-release-apk")
        output = self.repo / "original-bundle"
        validation = release.validation_summary([], "passed", "passed", "passed", "2026-09-20T00:00:00Z")
        release.bundle(self.repo, record, apk, output,
                       {"version_code": record.version_code, "certificate_sha256": release.CERT}, validation)
        local = release.verify_bundle(self.repo, record, output)
        names = sorted(local)
        tag_object = git(self.repo, "rev-parse", record.tag)
        for existing_names in (names[:1], names):
            uploaded = []
            edited = []
            draft = {"id": 15, "tag_name": record.tag, "draft": True, "prerelease": True,
                     "assets": [{"name": name, "digest": "sha256:" + local[name]} for name in existing_names]}

            def client(repo, *args, **kwargs):
                if args[:2] == ("api", f"repos/{release.REPOSITORY}/releases/tags/{record.tag}"):
                    return subprocess.CompletedProcess([], 1, "", "gh: Not Found (HTTP 404)")
                if args[:2] == ("api", f"repos/{release.REPOSITORY}/releases?per_page=100"):
                    return subprocess.CompletedProcess([], 0, json.dumps([[draft]]), "")
                if args[:2] == ("release", "upload"):
                    self.assertEqual(record.tag, args[2])
                    path = Path(args[3])
                    self.assertEqual(local[path.name], release.sha256(path))
                    self.assertNotIn(path.name, existing_names)
                    self.assertNotIn("--clobber", args)
                    uploaded.append(path.name)
                    return subprocess.CompletedProcess([], 0, "", "")
                if args[:2] == ("release", "edit"):
                    self.assertEqual(record.tag, args[2])
                    self.assertIn("--draft=false", args)
                    edited.append(args[2])
                    return subprocess.CompletedProcess([], 0, "", "")
                self.fail(f"Unexpected remote action: {args[:2]}")

            def remote(repo, name, ref):
                return record.source_sha if ref.endswith("^{}") else tag_object

            with self.subTest(existing=existing_names), patch.object(release, "gh", side_effect=client), \
                    patch.object(release, "remote_sha", side_effect=remote), \
                    patch.object(release, "remote_heads_current", return_value=True):
                self.assertEqual("published", release.publish(self.repo, record, output))
            self.assertEqual(sorted(set(names) - set(existing_names)), sorted(uploaded))
            self.assertEqual([record.tag], edited)
            self.assertEqual(local, release.verify_bundle(self.repo, record, output))

    def test_blocked_sync_comment_is_deduplicated_for_same_source_and_upstream(self):
        marker = release.blocked_sync_marker(self.upstream, "a" * 40)
        prior = subprocess.CompletedProcess([], 0, json.dumps([[{"body": "Previous failure " + marker}]]), "")
        with patch.object(release, "gh", return_value=prior) as client:
            release.record_blocked_sync(self.repo, self.upstream, "a" * 40)
        self.assertEqual(1, client.call_count)
        empty = subprocess.CompletedProcess([], 0, "[[]]", "")
        with patch.object(release, "gh", side_effect=[empty, subprocess.CompletedProcess([], 0, "{}", "")]) as client:
            release.record_blocked_sync(self.repo, self.upstream, "a" * 40)
        self.assertEqual(2, client.call_count)


    def test_documentation_only_fork_commit_reuses_release_but_source_or_upstream_change_does_not(self):
        record = release.reserve_local(self.repo, "HEAD", self.upstream)
        (self.repo / "docs").mkdir()
        (self.repo / "docs" / "validation.json").write_text('{"status":"updated"}')
        (self.repo / "CONTEXT.md").write_text("Updated glossary")
        git(self.repo, "add", "docs", "CONTEXT.md")
        git(self.repo, "commit", "-qm", "validation report update")
        docs_sha = git(self.repo, "rev-parse", "HEAD")
        self.assertEqual(record, release.equivalent_reservation(self.repo, docs_sha, self.upstream))
        self.assertIsNone(release.equivalent_reservation(self.repo, docs_sha, docs_sha))
        source = self.commit("actual application change\n")
        self.assertIsNone(release.equivalent_reservation(self.repo, source, self.upstream))
        next_record = release.reserve_local(self.repo, source, self.upstream)
        self.assertEqual(16, next_record.number)
        self.assertEqual(2, len(release.reservations(self.repo)))


    def test_rollback_to_an_older_released_tree_allocates_a_new_increasing_version(self):
        first = release.reserve_local(self.repo, "HEAD", self.upstream)
        second_source = self.commit("newer released application\n")
        second = release.reserve_local(self.repo, second_source, self.upstream)
        rollback_source = self.commit("original\n")
        self.assertTrue(release.same_release_inputs(self.repo, first.source_sha, rollback_source))
        self.assertIsNone(release.equivalent_reservation(self.repo, rollback_source, self.upstream))
        rollback = release.reserve_local(self.repo, rollback_source, self.upstream)
        self.assertEqual(17, rollback.number)
        self.assertGreater(rollback.version_code, second.version_code)
        self.assertEqual(rollback_source, rollback.source_sha)

    def test_retry_uses_earliest_original_bundle_and_never_rebuilds_after_partial_upload(self):
        record = release.reserve_local(self.repo, "HEAD", self.upstream)
        first = {"id": 10, "name": f"{record.tag}-123-bundle", "expired": False,
                 "digest": "sha256:" + "a" * 64,
                 "workflow_run": {"id": 123, "head_branch": release.RELEASE_BRANCH}}
        later = {**first, "id": 20, "name": f"{record.tag}-456-bundle",
                 "workflow_run": {"id": 456, "head_branch": release.RELEASE_BRANCH}}
        self.assertEqual(first, release.select_original_bundle(record, [later, first], True))
        self.assertIsNone(release.select_original_bundle(record, [], False))
        with self.assertRaises(release.ReleaseError):
            release.select_original_bundle(record, [], True)
        for invalid in (
            {**first, "expired": True},
            {**first, "digest": None},
            {**first, "workflow_run": {"id": 123, "head_branch": "unrelated"}},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(release.ReleaseError):
                release.select_original_bundle(record, [invalid, later], True)

    def test_original_bundle_retry_restores_exact_bytes_and_rejects_digest_or_zip_members(self):
        record = release.reserve_local(self.repo, "HEAD", self.upstream)
        apk = self.repo / "fixture.apk"
        apk.write_bytes(b"already-verified-synthetic-apk")
        original = self.repo / "original-bundle"
        validation = release.validation_summary([], "passed", "passed", "passed", "2026-09-20T00:00:00Z")
        with patch.dict(release.os.environ, {"GITHUB_RUN_ID": "123"}):
            release.bundle(self.repo, record, apk, original,
                           {"version_code": record.version_code, "certificate_sha256": release.CERT}, validation)
        expected = release.verify_bundle(self.repo, record, original)
        archive = self.repo / "original.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            for file in sorted(original.iterdir()):
                zipped.write(file, arcname=file.name)
        artifact = {"id": 10, "name": f"{record.tag}-123-bundle", "expired": False,
                    "digest": "sha256:" + release.sha256(archive),
                    "workflow_run": {"id": 123, "head_branch": release.RELEASE_BRANCH}}
        restored = self.repo / "restored-bundle"
        with patch.dict(release.os.environ, {"GITHUB_RUN_ID": "999"}):
            release.extract_original_bundle(self.repo, record, archive, restored, artifact)
        self.assertEqual(expected, release.verify_bundle(self.repo, record, restored))
        self.assertEqual((original / "manifest.json").read_bytes(), (restored / "manifest.json").read_bytes())
        missing = release.assets_to_upload(expected, {"SHA256SUMS": expected["SHA256SUMS"]}, False)
        self.assertEqual(set(expected) - {"SHA256SUMS"}, set(missing))
        with self.assertRaises(release.ReleaseError):
            release.extract_original_bundle(self.repo, record, archive, self.repo / "wrong-digest",
                                            {**artifact, "digest": "sha256:" + "0" * 64})
        with zipfile.ZipFile(archive, "a") as zipped:
            zipped.writestr("../escaped.txt", "must never be written")
        with self.assertRaises(release.ReleaseError):
            release.extract_original_bundle(self.repo, record, archive, self.repo / "wrong-members",
                                            {**artifact, "digest": "sha256:" + release.sha256(archive)})
        self.assertFalse((self.repo / "escaped.txt").exists())

    def test_release_description_distinguishes_untested_from_observed_partial_validation(self):
        record = release.reserve_local(self.repo, "HEAD", self.upstream)
        untested = release.validation_summary([], "not_run", "not_run", "not_run", "2026-09-20T00:00:00Z")
        title, notes = release.release_description(record, untested)
        self.assertIn("Untested", title)
        observed = release.validation_summary([], "passed", "passed", "passed", "2026-09-20T00:00:00Z")
        title, notes = release.release_description(record, observed)
        self.assertIn("Partially tested", title)
        self.assertIn(record.source_sha, notes)
        observed["device_validation"] = {
            "status": "passed", "source_sha": record.source_sha, "apk_sha256": "a" * 64,
            "scope": "Focused hash-bound fixture tests", "runs": [
                {"kind": "k90", "tested_package": "app.mihon.benchmark",
                 "tested_apk_sha256": "b" * 64, "instrumentation_apk_sha256": "c" * 64, "status": "passed", "page_size_bytes": 16384, "tests": 2,
                 "failures": 0, "errors": 0, "skipped": 0, "recorded_at": "2026-09-20T00:00:00Z"},
                {"kind": "emulator16k", "tested_package": "app.mihon.benchmark",
                 "tested_apk_sha256": "b" * 64, "instrumentation_apk_sha256": "c" * 64, "status": "passed", "page_size_bytes": 16384, "tests": 2,
                 "failures": 0, "errors": 0, "skipped": 0, "recorded_at": "2026-09-20T00:00:00Z"},
            ],
        }
        title, notes = release.release_description(record, observed)
        self.assertIn("Partially tested", title)
        self.assertIn("k90", notes.lower())
        self.assertIn("16 kb emulator", notes.lower())
        self.assertIn("app.mihon.benchmark", notes)
        self.assertIn("b" * 64, notes)
        self.assertIn("c" * 64, notes)
        self.assertIn("pending", notes.lower())

    def test_device_receipt_is_source_and_apk_bound_and_rejects_unstructured_content(self):
        record = release.reserve_local(self.repo, "HEAD", self.upstream)
        receipt = {
            "schema": 1, "source_sha": record.source_sha, "apk_sha256": "a" * 64,
            "runs": [{"kind": "k90", "tested_package": "app.mihon.benchmark",
                 "tested_apk_sha256": "b" * 64, "instrumentation_apk_sha256": "c" * 64, "page_size_bytes": 16384, "status": "passed", "tests": 7,
                      "failures": 0, "errors": 0, "skipped": 0, "recorded_at": "2026-09-20T00:00:00Z"},
                     {"kind": "emulator16k", "tested_package": "app.mihon.benchmark",
                 "tested_apk_sha256": "b" * 64, "instrumentation_apk_sha256": "c" * 64, "page_size_bytes": 16384, "status": "passed", "tests": 7,
                      "failures": 0, "errors": 0, "skipped": 0, "recorded_at": "2026-09-20T00:00:00Z"}],
        }
        result = release.device_summary(receipt, record, "a" * 64)
        self.assertEqual("passed", result["status"])
        self.assertEqual(2, len(result["runs"]))
        self.assertEqual("a" * 64, result["apk_sha256"])
        self.assertTrue(all(run["tested_package"] == "app.mihon.benchmark" for run in result["runs"]))
        self.assertTrue(all(run["tested_apk_sha256"] == "b" * 64 for run in result["runs"]))
        self.assertTrue(all(run["instrumentation_apk_sha256"] == "c" * 64 for run in result["runs"]))
        direct = {**receipt, "runs": [
            {**run, "tested_package": "app.mihon", "tested_apk_sha256": "a" * 64} for run in receipt["runs"]
        ]}
        self.assertEqual("passed", release.device_summary(direct, record, "a" * 64)["status"])
        for field in ("tested_package", "tested_apk_sha256", "instrumentation_apk_sha256"):
            missing = {**receipt, "runs": [{key: value for key, value in receipt["runs"][0].items() if key != field}]}
            with self.subTest(missing=field), self.assertRaises(release.ReleaseError):
                release.device_summary(missing, record, "a" * 64)
        for modified in (
            {**receipt, "source_sha": "b" * 40},
            {**receipt, "apk_sha256": "b" * 64},
            {**receipt, "raw_logs": "never export this"},
            {**receipt, "runs": [{**receipt["runs"][0], "tested_package": "app.mihon"}]},
            {**receipt, "runs": [{**receipt["runs"][0], "tested_package": "app.mihon.dev"}]},
            {**receipt, "runs": [{**receipt["runs"][0], "tested_apk_sha256": "not-a-hash"}]},
            {**receipt, "runs": [{**receipt["runs"][0], "tested_apk_sha256": "B" * 64}]},
            {**receipt, "runs": [{**receipt["runs"][0], "instrumentation_apk_sha256": ""}]},
            {**receipt, "runs": [{**receipt["runs"][0], "instrumentation_apk_sha256": None}]},
            {**receipt, "runs": [{**receipt["runs"][1], "page_size_bytes": 4096}]},
            {**receipt, "runs": [{**receipt["runs"][0], "tests": -1}]},
            {**receipt, "runs": [{**receipt["runs"][0], "failures": 1}]},
        ):
            with self.subTest(receipt=modified), self.assertRaises(release.ReleaseError):
                release.device_summary(modified, record, "a" * 64)


if __name__ == "__main__":
    unittest.main()
