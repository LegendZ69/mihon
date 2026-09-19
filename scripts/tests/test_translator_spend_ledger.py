"""Offline spend-ledger tests. All rates below are synthetic except fixture checks."""

from concurrent.futures import ThreadPoolExecutor
import datetime as dt
from decimal import Decimal
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import translator_spend_ledger as spend


SCRIPT = Path(spend.__file__)


def configuration():
    day = spend.today().isoformat()
    return {
        "format": 1,
        "profiles": {
            "synthetic": {
                "provider": "offline_test", "model": "test_model", "location": "global",
                "capacity": "shared", "currency": "USD", "verified_on": day,
                "source_url": "https://example.org/test-only-prices",
                "input_per_million_usd": "1000000", "output_per_million_usd": "1000000",
                "max_input_tokens": 1048576, "max_output_including_reasoning_tokens": 65536,
            },
        },
        "fx": {"sgd_per_usd": "1", "observed_on": day, "verified_on": day,
               "source_url": "https://example.org/test-only-fx"},
    }


class SpendLedgerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / "private-session"
        self.ledger = spend.Ledger(self.directory)
        self.config = configuration()
        self.ledger.init(self.config)

    def reserve(self, input_tokens=9, image_tokens=3, output_tokens=1, attempts=1, request_id=None):
        return self.ledger.reserve("synthetic", input_tokens, image_tokens, output_tokens, attempts, request_id)

    def settle(self, request_id, input_tokens=6, image_tokens=3, output_tokens=1, reasoning_tokens=1, attempts=1):
        return self.ledger.settle(request_id, input_tokens=input_tokens, image_tokens=image_tokens,
                                  output_tokens=output_tokens, reasoning_tokens=reasoning_tokens, attempts=attempts)

    def state(self):
        return json.loads(self.ledger.path.read_text())

    def test_exact_boundary_includes_all_outstanding_reservations(self):
        first = self.reserve(input_tokens=269, image_tokens=0)
        self.assertEqual("270.000000", first["reservation"]["sgd"])
        status = self.ledger.status()
        self.assertFalse(status["new_requests_allowed"])
        self.assertEqual(Decimal(270), Decimal(status["conservative_exposure_sgd"]))
        with self.assertRaises(spend.LedgerError):
            self.reserve(input_tokens=0, image_tokens=0)
        self.assertEqual(1, len(self.state()["requests"]))

    def test_rejection_preserves_audit_and_money(self):
        self.reserve(input_tokens=129)
        before = self.ledger.path.read_bytes()
        with self.assertRaises(spend.LedgerError):
            self.reserve(input_tokens=140)
        self.assertEqual(before, self.ledger.path.read_bytes())

    def test_multiple_reservations_and_settlement_across_restart(self):
        first = self.reserve(input_tokens=9, output_tokens=2)
        self.reserve(input_tokens=19)
        self.ledger = spend.Ledger(self.directory)
        status = self.settle(first["request_id"])
        self.assertEqual(Decimal(8), Decimal(status["estimated_usage_sgd"]))
        self.assertEqual(Decimal(20), Decimal(status["outstanding_reservations_sgd"]))
        self.assertEqual(Decimal(28), Decimal(status["conservative_exposure_sgd"]))
        self.assertEqual("0", status["recorded_billing_sgd"])

    def test_timeout_retains_reservation_until_complete_usage(self):
        item = self.reserve(output_tokens=2)
        status = self.ledger.settle(item["request_id"], uncertain=True)
        self.assertEqual("uncertain", status["requests"][item["request_id"]]["state"])
        self.assertEqual(Decimal(11), Decimal(status["outstanding_reservations_sgd"]))
        self.ledger = spend.Ledger(self.directory)
        with self.assertRaises(spend.LedgerError):
            self.ledger.settle(item["request_id"], input_tokens=1, output_tokens=1)
        self.assertEqual(Decimal(11), Decimal(self.ledger.status()["outstanding_reservations_sgd"]))
        self.assertEqual(Decimal(8), Decimal(self.settle(item["request_id"])["conservative_exposure_sgd"]))

    def test_partial_usage_cannot_release_money(self):
        item = self.reserve()
        with self.assertRaises(spend.LedgerError):
            self.ledger.settle(item["request_id"], input_tokens=1, image_tokens=0, output_tokens=1, attempts=1)
        self.assertEqual("reserved", self.state()["requests"][item["request_id"]]["state"])

    def test_unknown_image_subset_is_persisted_as_null_without_changing_cost(self):
        known = self.reserve(output_tokens=2)
        unknown = self.reserve(output_tokens=2)
        self.settle(known["request_id"])
        self.settle(unknown["request_id"], image_tokens=None)
        requests = self.state()["requests"]
        self.assertIsNone(requests[unknown["request_id"]]["usage"]["image_tokens"])
        self.assertEqual(requests[known["request_id"]]["usage_estimate"],
                         requests[unknown["request_id"]]["usage_estimate"])
        self.assertEqual(Decimal(8), Decimal(requests[unknown["request_id"]]["usage_estimate"]["sgd"]))
        audit = self.state()["audit"][-1]
        self.assertIsNone(audit["usage"]["image_tokens"])
        self.ledger = spend.Ledger(self.directory)
        before = self.ledger.path.read_bytes()
        self.settle(unknown["request_id"], image_tokens=None)
        self.assertEqual(before, self.ledger.path.read_bytes())

    def test_unknown_image_subset_does_not_allow_incomplete_cost_bearing_usage(self):
        item = self.reserve(output_tokens=2)
        complete = {"input_tokens": 6, "image_tokens": None, "output_tokens": 1,
                    "reasoning_tokens": 1, "attempts": 1}
        for field in ("input_tokens", "output_tokens", "reasoning_tokens", "attempts"):
            incomplete = dict(complete)
            del incomplete[field]
            with self.subTest(field=field), self.assertRaises(spend.LedgerError):
                self.ledger.settle(item["request_id"], **incomplete)
        self.assertEqual("reserved", self.state()["requests"][item["request_id"]]["state"])

    def test_known_image_subset_remains_validated_against_total_input(self):
        item = self.reserve(output_tokens=2)
        for invalid in (7, -1, True, "unknown"):
            with self.subTest(subset=invalid), self.assertRaises(spend.LedgerError):
                self.settle(item["request_id"], input_tokens=6, image_tokens=invalid)
        self.assertEqual("reserved", self.state()["requests"][item["request_id"]]["state"])

    def test_cli_can_settle_complete_totals_without_image_token_breakdown(self):
        item = self.reserve(output_tokens=2)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--ledger", str(self.directory), "settle", item["request_id"],
             "--input-tokens", "6", "--output-tokens", "1", "--reasoning-tokens", "1", "--attempts", "1"],
            capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        request = json.loads(result.stdout)["requests"][item["request_id"]]
        self.assertIsNone(request["usage"]["image_tokens"])
        self.assertEqual(Decimal(8), Decimal(request["usage_estimate"]["sgd"]))

    def test_confirmed_unsent_releases_reservation_idempotently(self):
        item = self.reserve()
        status = self.ledger.settle(item["request_id"], not_sent=True)
        self.assertEqual("0", status["conservative_exposure_sgd"])
        before = self.ledger.path.read_bytes()
        self.ledger.settle(item["request_id"], not_sent=True)
        self.assertEqual(before, self.ledger.path.read_bytes())

    def test_duplicate_uuid_never_grants_another_dispatch(self):
        request_id = str(uuid.uuid4())
        self.reserve(request_id=request_id)
        before = self.ledger.path.read_bytes()
        with self.assertRaises(spend.LedgerError):
            self.reserve(request_id=request_id)
        self.assertEqual(before, self.ledger.path.read_bytes())

    def test_usage_above_reservation_is_saved_and_halts(self):
        item = self.reserve()
        status = self.settle(item["request_id"], input_tokens=305)
        self.assertEqual(Decimal(307), Decimal(status["estimated_usage_sgd"]))
        self.assertTrue(status["hard_ceiling_exceeded"])
        self.assertFalse(status["new_requests_allowed"])
        self.assertTrue(self.state()["halted"])
        self.assertEqual("settled", self.state()["requests"][item["request_id"]]["state"])
        with self.assertRaises(spend.LedgerError):
            self.reserve()

    def test_excess_attempts_halt_even_when_usage_cost_is_small(self):
        item = self.reserve()
        status = self.settle(item["request_id"], input_tokens=1, image_tokens=0,
                             output_tokens=0, reasoning_tokens=0, attempts=2)
        self.assertFalse(status["new_requests_allowed"])
        self.assertEqual(Decimal(1), Decimal(status["conservative_exposure_sgd"]))

    def test_reservation_covers_every_retry_and_reasoning_is_not_double_counted(self):
        item = self.reserve(input_tokens=2, image_tokens=1, output_tokens=3, attempts=4)
        self.assertEqual(Decimal(20), Decimal(item["reservation"]["sgd"]))
        status = self.settle(item["request_id"], input_tokens=8, image_tokens=4,
                             output_tokens=4, reasoning_tokens=8, attempts=4)
        self.assertEqual(Decimal(20), Decimal(status["estimated_usage_sgd"]))
        self.assertTrue(status["new_requests_allowed"])

    def test_billing_is_separate_and_does_not_reduce_estimate_for_credits(self):
        item = self.reserve(output_tokens=2)
        self.settle(item["request_id"])
        status = self.ledger.bill(item["request_id"], "4", spend.today().isoformat())
        self.assertEqual("4", status["recorded_billing_sgd"])
        self.assertEqual(Decimal(8), Decimal(status["estimated_usage_sgd"]))
        self.assertEqual(Decimal(8), Decimal(status["conservative_exposure_sgd"]))

    def test_higher_confirmed_bill_counts_without_adding_usage_twice(self):
        item = self.reserve(output_tokens=2)
        self.settle(item["request_id"])
        status = self.ledger.bill(item["request_id"], "10", spend.today().isoformat())
        self.assertEqual(Decimal(10), Decimal(status["conservative_exposure_sgd"]))
        self.assertTrue(status["new_requests_allowed"])

    def test_uncertain_billed_request_keeps_reservation(self):
        item = self.reserve()
        self.ledger.settle(item["request_id"], uncertain=True)
        status = self.ledger.bill(item["request_id"], "2", spend.today().isoformat())
        self.assertEqual(Decimal(10), Decimal(status["conservative_exposure_sgd"]))
        with self.assertRaises(spend.LedgerError):
            self.ledger.settle(item["request_id"], not_sent=True)

    def test_bill_above_reservation_is_saved_and_halts(self):
        item = self.reserve()
        status = self.ledger.bill(item["request_id"], "310", spend.today().isoformat())
        self.assertTrue(status["hard_ceiling_exceeded"])
        self.assertFalse(status["new_requests_allowed"])
        self.assertEqual("310", status["recorded_billing_sgd"])

    def test_duplicate_settlement_is_safe_but_conflict_is_refused(self):
        item = self.reserve(output_tokens=2)
        self.settle(item["request_id"])
        before = self.ledger.path.read_bytes()
        self.settle(item["request_id"])
        self.assertEqual(before, self.ledger.path.read_bytes())
        with self.assertRaises(spend.LedgerError):
            self.settle(item["request_id"], input_tokens=5)

    def test_stale_config_blocks_reservation_but_allows_settlement_and_refresh(self):
        item = self.reserve(output_tokens=2)
        later = spend.today() + dt.timedelta(days=20)
        with patch.object(spend, "today", return_value=later):
            self.assertFalse(self.ledger.status()["new_requests_allowed"])
            with self.assertRaises(spend.LedgerError):
                self.reserve()
            self.settle(item["request_id"])
            fresh = configuration()
            fresh["profiles"]["synthetic"]["input_per_million_usd"] = "2000000"
            self.ledger.refresh(fresh)
            status = self.ledger.status()
            self.assertTrue(status["new_requests_allowed"])
            self.assertEqual(Decimal(8), Decimal(status["conservative_exposure_sgd"]))
            self.assertEqual("1000000", status["requests"][item["request_id"]]["pricing"]["input_per_million_usd"])

    def test_private_permissions_and_symlink_refusal(self):
        self.assertEqual(0o700, stat.S_IMODE(self.directory.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(self.ledger.path.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((self.directory / "ledger.lock").stat().st_mode))
        self.ledger.path.chmod(0o644)
        with self.assertRaises(spend.LedgerError):
            self.ledger.status()
        self.ledger.path.chmod(0o600)
        linked = Path(self.temporary.name) / "linked"
        linked.symlink_to(self.directory)
        with self.assertRaises(spend.LedgerError):
            spend.Ledger(linked).status()

    def test_atomic_write_failure_keeps_previous_reservations(self):
        self.reserve()
        before = self.ledger.path.read_bytes()
        with patch.object(spend.os, "replace", side_effect=OSError("simulated full disk")):
            with self.assertRaises(OSError):
                self.reserve()
        self.assertEqual(before, self.ledger.path.read_bytes())
        self.assertEqual([], list(self.directory.glob(".ledger-*")))

    def test_cannot_reinitialize_or_reset_existing_spend(self):
        self.reserve()
        before = self.ledger.path.read_bytes()
        with self.assertRaises(spend.LedgerError):
            self.ledger.init(configuration())
        self.assertEqual(before, self.ledger.path.read_bytes())

    def test_unknown_pricing_and_invalid_bounds_fail_closed(self):
        with self.assertRaises(spend.LedgerError):
            self.ledger.reserve("unknown-provider", 1, 0, 1, 1)
        for kwargs in ({"image_tokens": 10}, {"input_tokens": -1}, {"output_tokens": 0},
                       {"output_tokens": 65537}, {"input_tokens": 1048577}, {"attempts": 0},
                       {"input_tokens": True}, {"request_id": "sk-test-private-secret"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(spend.LedgerError):
                self.reserve(**kwargs)

    def test_parallel_processes_cannot_overspend_or_lose_audit_records(self):
        command = [sys.executable, str(SCRIPT), "--ledger", str(self.directory), "reserve",
                   "--profile", "synthetic", "--input-tokens", "9", "--image-tokens", "3",
                   "--max-output-tokens", "1", "--max-attempts", "1"]
        def run(_):
            return subprocess.run(command, capture_output=True, text=True, timeout=20)
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(run, range(34)))
        self.assertEqual(27, sum(result.returncode == 0 for result in results))
        self.assertEqual(7, sum(result.returncode == 2 for result in results))
        status = self.ledger.status()
        self.assertEqual(Decimal(270), Decimal(status["conservative_exposure_sgd"]))
        state = self.state()
        self.assertEqual(27, len(state["requests"]))
        self.assertEqual(list(range(1, 29)), [entry["sequence"] for entry in state["audit"]])

    def test_raw_secrets_and_payload_fields_never_enter_audit(self):
        fresh = configuration()
        fresh["api_key"] = "secret-do-not-save"
        fresh["fx"]["comment"] = "secret-do-not-save"
        fresh["profiles"]["synthetic"]["authorization"] = "secret-do-not-save"
        self.ledger.refresh(fresh)
        self.reserve()
        self.assertNotIn("secret-do-not-save", self.ledger.path.read_text())
        self.assertNotIn("authorization", self.ledger.path.read_text())

    def test_cli_error_does_not_echo_a_secret_config_value(self):
        path = Path(self.temporary.name) / "bad-config.json"
        bad = configuration()
        bad["fx"]["sgd_per_usd"] = "private-test-key"
        path.write_text(json.dumps(bad))
        result = subprocess.run([sys.executable, str(SCRIPT), "--ledger", str(self.directory),
                                 "refresh", "--config", str(path)], capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)
        self.assertNotIn("private-test-key", result.stderr + result.stdout)


class ConfigurationTests(unittest.TestCase):
    def test_current_fixture_preserves_official_observations_without_credit_discount(self):
        config = spend.load_json(spend.DEFAULT_CONFIG)
        value = spend.validate_config(config, on=dt.date(2026, 9, 6))
        price = value["profiles"]["vertex-3.8-global-shared"]
        self.assertEqual("1.50", price["input_per_million_usd"])
        self.assertEqual("7.50", price["output_per_million_usd"])
        self.assertEqual("1.1622", value["fx"]["usd_per_eur"])
        self.assertEqual("1.4724", value["fx"]["sgd_per_eur"])
        self.assertEqual("1.266907589056", value["fx"]["sgd_per_usd"])
        maximum = spend.estimate(price, value["fx"], 1048576, 65536, 6)
        self.assertEqual("12.386304", maximum["usd"])
        self.assertEqual("15.692303", maximum["sgd"])

    def test_amount_and_fx_validation(self):
        for invalid in ("NaN", "Infinity", "-1", "-Infinity", "", "1e999", "0.0000000000000000001", True, 1.2):
            with self.subTest(invalid=invalid), self.assertRaises(spend.LedgerError):
                spend.decimal(invalid)
        for field, invalid in (("sgd_per_usd", "0"), ("sgd_per_usd", "NaN"),
                               ("observed_on", "2026-02-30"), ("observed_on", "2999-01-01"),
                               ("source_url", "https://[invalid/"),
                               ("source_url", "https://user:secret@example.org/fx"),
                               ("source_url", "https://example.org/fx?api_key=secret")):
            config = configuration()
            config["fx"][field] = invalid
            with self.subTest(field=field, invalid=invalid), self.assertRaises(spend.LedgerError):
                spend.validate_config(config)

    def test_missing_rates_and_inconsistent_fx_fail_closed(self):
        for field in ("input_per_million_usd", "output_per_million_usd", "verified_on", "max_input_tokens"):
            config = configuration()
            del config["profiles"]["synthetic"][field]
            with self.subTest(field=field), self.assertRaises(spend.LedgerError):
                spend.validate_config(config)
        config = spend.load_json(spend.DEFAULT_CONFIG)
        config["fx"]["sgd_per_usd"] = "1.26"
        with self.assertRaises(spend.LedgerError):
            spend.validate_config(config, on=dt.date(2026, 9, 6))

    def test_estimates_round_up_instead_of_under_reserving(self):
        config = configuration()
        price = config["profiles"]["synthetic"]
        price["input_per_million_usd"] = "0.00000001"
        price["output_per_million_usd"] = "0.00000001"
        self.assertEqual("0.000001", spend.estimate(price, config["fx"], 1, 1)["sgd"])


if __name__ == "__main__":
    unittest.main()
