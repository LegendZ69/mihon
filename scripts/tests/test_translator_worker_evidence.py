import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("worker_evidence", Path(__file__).resolve().parents[1] / "translator_worker_evidence.py")
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)

RUN = "a3f773ec-7c56-42ac-a0d8-3ff7ac7f9eaa"


def fixture(*, bad_manifest=False):
    images = []
    entries = {}
    for index in range(2):
        body = b"\x89PNG\r\n\x1a\n" + b"fixture" + bytes([index])
        entries[f"page-{index}.png"] = body
        images.append({"id": str(index), "index": index, "contentHash": hashlib.sha256(body).hexdigest(), "byteSize": len(body)})
    # The first committed page is intentionally source index 1.
    first = {"imageId": "1", "imageHash": images[1]["contentHash"], "revision": 1}
    report = {"schema": 1, "run_id": RUN, "package": "app.mihon.benchmark", "cloud_forwarding": False,
              "status": "passed", "credential_removed": True, "owned_job_removed": True, "settings_unchanged": True,
              "images": images, "records": [{"results": [first]}, {"results": [first, {"imageId": "0"}]}]}
    entries["report.json"] = json.dumps(report).encode()
    entries["telemetry.json"] = json.dumps({"schema": 1, "job_id": "worker-fixture-" + RUN,
                                          "synthetic": True, "operations": [], "events": [], "usage": []}).encode()
    manifest = {"schema": 1, "run_id": RUN, "package": "app.mihon.benchmark",
                "first_committed_page": {"image_id": "1", "source_index": 1, "image_sha256": images[1]["contentHash"], "result_revision": 1},
                "files": [{"name": name, "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()} for name, body in entries.items()]}
    if bad_manifest:
        manifest["files"][0]["sha256"] = "0" * 64
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, body in entries.items():
            archive.writestr(name, body)
        archive.writestr("manifest.json", json.dumps(manifest))
    archive = output.getvalue()
    digest = hashlib.sha256(archive).hexdigest()
    chunks = [archive[index:index + 120] for index in range(0, len(archive), 120)]
    lines = []
    def block(values):
        lines.extend(f"INSTRUMENTATION_STATUS: {key}={value}" for key, value in values.items())
        lines.append("INSTRUMENTATION_STATUS_CODE: 0")
    block({"translation_worker_evidence_json": json.dumps({"phase": "collect", "run_id": RUN, "sha256": digest, "bytes": len(archive), "chunks": len(chunks), "manifest": manifest})})
    for index, chunk in enumerate(chunks):
        block({"translation_worker_evidence_chunk": base64.b64encode(chunk).decode(), "translation_worker_evidence_chunk_index": index, "translation_worker_evidence_run": RUN})
    block({"translation_worker_evidence_json": json.dumps({"phase": "collect_complete", "run_id": RUN, "sha256": digest, "bytes": len(archive)})})
    lines.append("OK (1 test)")
    return "\n".join(lines), archive


class WorkerEvidenceTests(unittest.TestCase):
    def test_verified_receipt_keeps_first_committed_page_identity(self):
        log, expected = fixture()
        archive, receipt = evidence.verify(log)
        self.assertEqual(expected, archive)
        self.assertEqual(hashlib.sha256(expected).hexdigest(), receipt["sha256"])
        self.assertEqual(1, receipt["first_committed_page"]["source_index"])
        self.assertTrue(receipt["cleanup_qualified"])

    def test_missing_or_duplicate_chunk_cannot_issue_a_receipt(self):
        log, _ = fixture()
        with self.assertRaises(ValueError):
            evidence.verify(log.replace("translation_worker_evidence_chunk_index=1\n", "translation_worker_evidence_chunk_index=0\n", 1))

    def test_archive_transport_hash_does_not_replace_entry_verification(self):
        log, _ = fixture(bad_manifest=True)
        with self.assertRaises(ValueError):
            evidence.verify(log)


if __name__ == "__main__":
    unittest.main()
