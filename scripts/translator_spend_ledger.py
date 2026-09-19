#!/usr/bin/env python3
"""Private, offline reservation ledger for translator validation; never calls providers.

Use one ledger for the entire S$300 session, including every retry and paid
diagnostic. New reservations stop at S$270 including unsettled requests. This is
an operator-controlled estimate, not an account-wide or provider-enforced cap.
No API keys, endpoints, prompts, responses, image names, or chapter names are
accepted. Only opaque local UUIDs identify requests. Keep the ledger outside Git.

Reserve --input-tokens includes ALL text, image, system, and schema tokens;
--image-tokens is a subset for inspection. --max-output-tokens includes reasoning.
Use documented model ceilings if smaller bounds are not established, and reserve
all possible attempts before an app action that might retry. Each reservation
authorizes only its named provider/profile. Separately billed tools are excluded.

Settle aggregates usage for ALL attempts: --output-tokens is visible output ONLY;
--reasoning-tokens is separate. Omit --image-tokens when its input-token subset is
unavailable; it is recorded as null, and complete total input still determines
cost. Missing cost-bearing totals and timeouts use --uncertain and retain the
full reservation. --not-sent releases only a confirmed unsent request.
Record confirmed SGD billing independently with 'bill'; credits never lower the
conservative exposure. Usage or billing above a reservation latches a stop for
manual investigation. Settlement always records excess spend; it is not rejected.
"""

import argparse
import contextlib
import datetime as dt
from decimal import Decimal, InvalidOperation, ROUND_CEILING, localcontext
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import uuid
from urllib.parse import urlsplit


HARD_CEILING = Decimal("300")
DISPATCH_CEILING = Decimal("270")
MONEY_UNIT = Decimal("0.000001")
MAX_PRICING_AGE_DAYS = 14
MAX_FX_AGE_DAYS = 7
DEFAULT_CONFIG = Path(__file__).parent / "fixtures" / "translation-pricing.json"
DEFAULT_LEDGER = Path.home() / ".local" / "state" / "mihon" / "translator-validation-spend"
DISCLAIMER = (
    "Local estimates and outstanding reservations; not provider-enforced, not billed amounts. "
    "Account SGD SKUs, FX, taxes and unrelated account usage may differ. "
    "Never dispatch outside this ledger or treat the ceiling as a spending target."
)


class LedgerError(ValueError):
    """A safe error whose message contains no untrusted field values."""


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def today():
    return dt.datetime.now(dt.timezone.utc).date()


def decimal(value, *, positive=False):
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        raise LedgerError("Amounts must be exact decimal strings or integers")
    try:
        parsed = Decimal(value)
    except InvalidOperation:
        raise LedgerError("Invalid decimal amount") from None
    if not parsed.is_finite() or parsed < 0 or (positive and parsed == 0):
        raise LedgerError("Amounts must be finite and non-negative; rates must be positive")
    if parsed > Decimal("1000000000000") or parsed.as_tuple().exponent < -18:
        raise LedgerError("Amount exceeds supported precision or range")
    return parsed


def integer(value, *, maximum=10**12):
    if type(value) is not int or not 0 <= value <= maximum:
        raise LedgerError("Token or attempt count is outside the supported integer range")
    return value


def number(value):
    return format(value, "f")


def date(value):
    try:
        result = dt.date.fromisoformat(value)
        if result.isoformat() != value:
            raise ValueError()
        return result
    except (TypeError, ValueError):
        raise LedgerError("Dates must use valid YYYY-MM-DD values") from None


def source(value):
    if not isinstance(value, str) or len(value) > 512:
        raise LedgerError("A documented HTTPS source URL is required")
    try:
        parsed = urlsplit(value)
    except ValueError:
        raise LedgerError("Malformed HTTPS source URL") from None
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or any(c.isspace() for c in value)):
        raise LedgerError("Source URLs must be HTTPS without credentials, queries, or fragments")
    return value


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}", value):
        raise LedgerError("Invalid pricing profile identifier")
    return value


def request_uuid(value):
    try:
        result = str(uuid.UUID(value))
        if result != value:
            raise ValueError()
        return result
    except (TypeError, ValueError, AttributeError):
        raise LedgerError("Request identifiers must be canonical local UUIDs") from None


def validate_config(config, on=None, *, fresh=True):
    """Select only documented fields; omit free-form notes from private audit state."""
    on = on or today()
    try:
        if config["format"] != 1 or not isinstance(config["profiles"], dict) or not config["profiles"]:
            raise LedgerError("Unsupported or empty pricing configuration")
        profiles = {}
        for key, item in config["profiles"].items():
            identifier(key)
            verified = date(item["verified_on"])
            if verified > on or (fresh and (on - verified).days > MAX_PRICING_AGE_DAYS):
                raise LedgerError("Pricing verification is future-dated or stale; reverify official prices")
            if item["currency"] != "USD":
                raise LedgerError("Only explicitly documented USD rate cards are supported")
            profile = {field: identifier(item[field]) for field in ("provider", "model", "location", "capacity")}
            profile.update({
                "currency": "USD", "verified_on": verified.isoformat(),
                "source_url": source(item["source_url"]),
                "input_per_million_usd": number(decimal(item["input_per_million_usd"], positive=True)),
                "output_per_million_usd": number(decimal(item["output_per_million_usd"], positive=True)),
                "max_input_tokens": integer(item["max_input_tokens"]),
                "max_output_including_reasoning_tokens": integer(item["max_output_including_reasoning_tokens"]),
            })
            if not profile["max_input_tokens"] or not profile["max_output_including_reasoning_tokens"]:
                raise LedgerError("Documented token limits must be positive")
            profiles[key] = profile
        item = config["fx"]
        observed, verified = date(item["observed_on"]), date(item["verified_on"])
        if observed > verified or verified > on or (fresh and (on - observed).days > MAX_FX_AGE_DAYS):
            raise LedgerError("FX observation is future-dated or stale; obtain a dated reference rate")
        rate = decimal(item["sgd_per_usd"], positive=True)
        fx = {
            "sgd_per_usd": number(rate), "observed_on": observed.isoformat(),
            "verified_on": verified.isoformat(), "source_url": source(item["source_url"]),
        }
        if "usd_per_eur" in item or "sgd_per_eur" in item:
            usd = decimal(item["usd_per_eur"], positive=True)
            sgd = decimal(item["sgd_per_eur"], positive=True)
            with localcontext() as ctx:
                ctx.prec = 60
                ceiling = (sgd / usd).quantize(Decimal("0.000000000001"), rounding=ROUND_CEILING)
            if rate != ceiling:
                raise LedgerError("FX cross-rate does not match its rounded-up source observations")
            fx.update(usd_per_eur=number(usd), sgd_per_eur=number(sgd))
        return {"format": 1, "profiles": profiles, "fx": fx}
    except (KeyError, TypeError, AttributeError):
        raise LedgerError("Pricing and FX configuration is incomplete or malformed") from None


def estimate(profile, fx, input_tokens, output_tokens, attempts=1):
    with localcontext() as ctx:
        ctx.prec = 60
        usd = ((Decimal(input_tokens) * decimal(profile["input_per_million_usd"])
                + Decimal(output_tokens) * decimal(profile["output_per_million_usd"]))
               * Decimal(attempts) / Decimal(1_000_000))
        sgd = (usd * decimal(fx["sgd_per_usd"])).quantize(MONEY_UNIT, rounding=ROUND_CEILING)
    return {"usd": number(usd), "sgd": number(sgd)}


def reject_constants(_):
    raise LedgerError("Non-finite JSON numbers are prohibited")


def load_json(path):
    try:
        with path.open() as stream:
            return json.load(stream, parse_float=Decimal, parse_constant=reject_constants)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise LedgerError("Malformed JSON; existing ledger was left untouched") from None


class Ledger:
    def __init__(self, directory):
        self.directory = Path(directory).absolute()
        self.path = self.directory / "ledger.json"

    def _private(self, path, *, directory=False):
        info = path.lstat()
        expected = stat.S_ISDIR if directory else stat.S_ISREG
        if (not expected(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) & 0o077 or (not directory and info.st_nlink != 1)):
            raise LedgerError("Ledger paths must be owned, private, regular files/directories without links")

    @contextlib.contextmanager
    def locked(self, *, create=False):
        if create:
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self._private(self.directory, directory=True)
        lock_path = self.directory / "ledger.lock"
        fd = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            self._private(lock_path)
            fcntl.flock(fd, fcntl.LOCK_EX)
            if self.path.exists() or self.path.is_symlink():
                self._private(self.path)
            yield
        finally:
            os.close(fd)

    def _read(self):
        state = load_json(self.path)
        if (not isinstance(state, dict) or state.get("format") != 1
                or state.get("hard_ceiling_sgd") != number(HARD_CEILING)
                or state.get("dispatch_ceiling_sgd") != number(DISPATCH_CEILING)
                or not isinstance(state.get("requests"), dict)
                or not isinstance(state.get("audit"), list)
                or type(state.get("halted")) is not bool):
            raise LedgerError("Invalid ledger structure or budget policy; refusing to reset state")
        validate_config(state["config"], fresh=False)
        for key, item in state["requests"].items():
            request_uuid(key)
            if item.get("state") not in ("reserved", "uncertain", "settled", "not_sent"):
                raise LedgerError("Invalid persisted request state")
            decimal(item["reservation"]["sgd"])
            if item["state"] == "settled":
                decimal(item["usage_estimate"]["sgd"])
            if item.get("billed_sgd") is not None:
                decimal(item["billed_sgd"])
        return state

    def _write(self, state):
        fd, temporary = tempfile.mkstemp(prefix=".ledger-", dir=self.directory)
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump(state, stream, indent=2, allow_nan=False)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            directory_fd = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    @staticmethod
    def _audit(state, event, **fields):
        state["audit"].append({"sequence": len(state["audit"]) + 1, "utc": utc(), "event": event, **fields})

    @staticmethod
    def _status(state):
        outstanding = Decimal(0)
        spent = Decimal(0)
        billed = Decimal(0)
        exposure = Decimal(0)
        for item in state["requests"].values():
            confirmed_bill = decimal(item["billed_sgd"]) if item.get("billed_sgd") is not None else Decimal(0)
            billed += confirmed_bill
            if item["state"] in ("reserved", "uncertain"):
                reserved = decimal(item["reservation"]["sgd"])
                outstanding += reserved
                exposure += max(reserved, confirmed_bill)
            elif item["state"] == "settled":
                usage = decimal(item["usage_estimate"]["sgd"])
                spent += usage
                exposure += max(usage, confirmed_bill)
            else:
                exposure += confirmed_bill
        available = max(Decimal(0), DISPATCH_CEILING - exposure)
        reasons = []
        if state["halted"]:
            reasons.append("Recorded cost exceeded a reservation; investigate before any more paid requests")
        if exposure >= DISPATCH_CEILING:
            reasons.append("S$270 dispatch stop reached")
        try:
            validate_config(state["config"])
        except LedgerError as error:
            reasons.append(str(error))
        return {
            "ledger_id": state["ledger_id"], "hard_ceiling_sgd": number(HARD_CEILING),
            "dispatch_ceiling_sgd": number(DISPATCH_CEILING), "estimated_usage_sgd": number(spent),
            "outstanding_reservations_sgd": number(outstanding), "recorded_billing_sgd": number(billed),
            "conservative_exposure_sgd": number(exposure), "available_to_reserve_sgd": number(available),
            "hard_ceiling_exceeded": exposure > HARD_CEILING, "new_requests_allowed": not reasons,
            "stop_reasons": reasons, "requests": state["requests"], "audit_records": len(state["audit"]),
            "pricing_and_fx": state["config"], "notice": DISCLAIMER,
        }

    def init(self, config):
        config = validate_config(config)
        with self.locked(create=True):
            if self.path.exists():
                raise LedgerError("Ledger already exists; reuse it so prior spending stays counted")
            state = {
                "format": 1, "ledger_id": str(uuid.uuid4()), "created_utc": utc(),
                "hard_ceiling_sgd": number(HARD_CEILING), "dispatch_ceiling_sgd": number(DISPATCH_CEILING),
                "config": config, "requests": {}, "audit": [], "halted": False,
            }
            self._audit(state, "initialized", config=config)
            self._write(state)
            return self._status(state)

    def refresh(self, config):
        config = validate_config(config)
        with self.locked():
            state = self._read()
            state["config"] = config
            self._audit(state, "pricing_and_fx_refreshed", config=config)
            self._write(state)
            return self._status(state)

    def status(self):
        with self.locked():
            return self._status(self._read())

    def reserve(self, profile, input_tokens, image_tokens, max_output_tokens, max_attempts, request_id=None):
        identifier(profile)
        integer(input_tokens)
        integer(image_tokens)
        integer(max_output_tokens)
        integer(max_attempts, maximum=1000)
        if image_tokens > input_tokens or not max_output_tokens or not max_attempts:
            raise LedgerError("Images must be a subset of input; output and attempt bounds must be positive")
        request_id = request_uuid(request_id) if request_id else str(uuid.uuid4())
        bounds = {"input_tokens": input_tokens, "image_tokens": image_tokens,
                  "output_including_reasoning_tokens": max_output_tokens, "max_attempts": max_attempts}
        with self.locked():
            state = self._read()
            # Never return a second dispatch permission for a duplicate operation.
            if request_id in state["requests"]:
                raise LedgerError("Request UUID already exists; inspect its status and never dispatch it twice")
            status = self._status(state)
            if not status["new_requests_allowed"]:
                raise LedgerError("New requests are stopped; inspect ledger status")
            price = state["config"]["profiles"].get(profile)
            if price is None:
                raise LedgerError("No verified pricing exists for this provider/profile")
            if (input_tokens > price["max_input_tokens"]
                    or max_output_tokens > price["max_output_including_reasoning_tokens"]):
                raise LedgerError("Request bounds exceed documented model token limits")
            fx = state["config"]["fx"]
            cost = estimate(price, fx, input_tokens, max_output_tokens, max_attempts)
            if decimal(status["conservative_exposure_sgd"]) + decimal(cost["sgd"]) > DISPATCH_CEILING:
                raise LedgerError("Reservation would exceed the S$270 dispatch stop including outstanding requests")
            item = {"state": "reserved", "profile": profile, "bounds": bounds,
                    "pricing": price, "fx": fx, "reservation": cost, "created_utc": utc()}
            state["requests"][request_id] = item
            self._audit(state, "reserved", request_id=request_id, reservation=cost, bounds=bounds)
            self._write(state)
            return {"request_id": request_id, "reservation": cost, "notice": DISCLAIMER}

    def settle(self, request_id, *, uncertain=False, not_sent=False, input_tokens=None,
               image_tokens=None, output_tokens=None, reasoning_tokens=None, attempts=None):
        request_uuid(request_id)
        usage_values = (input_tokens, image_tokens, output_tokens, reasoning_tokens, attempts)
        if uncertain and not_sent or ((uncertain or not_sent) and any(v is not None for v in usage_values)):
            raise LedgerError("Choose uncertainty, confirmed unsent, or complete usage exclusively")
        usage = None
        if not uncertain and not not_sent:
            for value in (input_tokens, output_tokens, reasoning_tokens, attempts):
                integer(value)
            if image_tokens is not None:
                integer(image_tokens)
            if (image_tokens is not None and image_tokens > input_tokens) or not attempts:
                raise LedgerError("Invalid aggregate usage; all completed attempts must be counted")
            usage = {"input_tokens": input_tokens, "image_tokens": image_tokens,
                     "output_tokens": output_tokens, "reasoning_tokens": reasoning_tokens, "attempts": attempts}
        with self.locked():
            state = self._read()
            item = state["requests"].get(request_id)
            if item is None:
                raise LedgerError("Unknown request UUID")
            if item["state"] in ("settled", "not_sent"):
                if (not_sent and item["state"] == "not_sent") or (usage and usage == item.get("usage")):
                    return self._status(state)
                raise LedgerError("Request is already finalized; conflicting settlement refused")
            if not_sent and item.get("billed_sgd") is not None:
                raise LedgerError("A request with recorded billing cannot be marked unsent")
            if uncertain:
                item["state"] = "uncertain"
                self._audit(state, "uncertain_reservation_retained", request_id=request_id)
            elif not_sent:
                item["state"] = "not_sent"
                self._audit(state, "confirmed_not_sent", request_id=request_id)
            else:
                cost = estimate(item["pricing"], item["fx"], input_tokens, output_tokens + reasoning_tokens)
                item.update(state="settled", usage=usage, usage_estimate=cost)
                bounds = item["bounds"]
                exceeded = (decimal(cost["sgd"]) > decimal(item["reservation"]["sgd"])
                            or attempts > bounds["max_attempts"]
                            or input_tokens > bounds["input_tokens"] * bounds["max_attempts"]
                            or output_tokens + reasoning_tokens
                            > bounds["output_including_reasoning_tokens"] * bounds["max_attempts"])
                item["reservation_exceeded"] = exceeded
                state["halted"] = state["halted"] or exceeded
                self._audit(state, "usage_settled", request_id=request_id, usage=usage,
                            usage_estimate=cost, reservation_exceeded=exceeded)
            item["updated_utc"] = utc()
            self._write(state)
            return self._status(state)

    def bill(self, request_id, amount, billed_on):
        request_uuid(request_id)
        amount = number(decimal(amount))
        observed = date(billed_on)
        if observed > today():
            raise LedgerError("Billing observation cannot be future-dated")
        with self.locked():
            state = self._read()
            item = state["requests"].get(request_id)
            if item is None or item["state"] == "not_sent":
                raise LedgerError("Billing requires a known request that may have been sent")
            if item.get("billed_sgd") is not None:
                if item["billed_sgd"] == amount and item["billed_on"] == billed_on:
                    return self._status(state)
                raise LedgerError("Billing already recorded; conflicting amount refused")
            item.update(billed_sgd=amount, billed_on=billed_on)
            exceeded = decimal(amount) > decimal(item["reservation"]["sgd"])
            state["halted"] = state["halted"] or exceeded
            self._audit(state, "billing_recorded", request_id=request_id, billed_sgd=amount,
                        billed_on=billed_on, reservation_exceeded=exceeded)
            self._write(state)
            return self._status(state)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER, help="Private session directory outside Git")
    actions = parser.add_subparsers(dest="action", required=True)
    for action in ("init", "refresh"):
        actions.add_parser(action).add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    actions.add_parser("status")
    reserve = actions.add_parser("reserve")
    reserve.add_argument("--profile", required=True)
    reserve.add_argument("--request-id", help="Local UUID; duplicates never grant dispatch permission")
    reserve.add_argument("--input-tokens", type=int, required=True, help="Upper bound including image/system/schema tokens")
    reserve.add_argument("--image-tokens", type=int, required=True, help="Subset of input tokens; use total input bound if unknown")
    reserve.add_argument("--max-output-tokens", type=int, required=True, help="Upper bound including visible output AND reasoning")
    reserve.add_argument("--max-attempts", type=int, required=True, help="Maximum paid attempts including automatic retries")
    settle = actions.add_parser("settle")
    settle.add_argument("request_id")
    completion = settle.add_mutually_exclusive_group()
    completion.add_argument("--uncertain", action="store_true", help="Retain entire reservation after timeout/missing usage")
    completion.add_argument("--not-sent", action="store_true", help="Release ONLY after confirming no request was sent")
    settle.add_argument("--image-tokens", type=int, help="Known input subset; omit to record unavailable as null")
    for argument in ("input-tokens", "output-tokens", "reasoning-tokens", "attempts"):
        settle.add_argument("--" + argument, type=int)
    bill = actions.add_parser("bill")
    bill.add_argument("request_id")
    bill.add_argument("--sgd", required=True, help="Confirmed billed SGD amount, separate from usage estimate")
    bill.add_argument("--billed-on", required=True)
    args = parser.parse_args(argv)
    ledger = Ledger(args.ledger)
    try:
        if args.action in ("init", "refresh"):
            result = getattr(ledger, args.action)(load_json(args.config))
        elif args.action == "reserve":
            result = ledger.reserve(args.profile, args.input_tokens, args.image_tokens, args.max_output_tokens,
                                    args.max_attempts, args.request_id)
        elif args.action == "settle":
            result = ledger.settle(args.request_id, uncertain=args.uncertain, not_sent=args.not_sent,
                                   input_tokens=args.input_tokens, image_tokens=args.image_tokens,
                                   output_tokens=args.output_tokens, reasoning_tokens=args.reasoning_tokens,
                                   attempts=args.attempts)
        elif args.action == "bill":
            result = ledger.bill(args.request_id, args.sgd, args.billed_on)
        else:
            result = ledger.status()
        print(json.dumps(result, indent=2, allow_nan=False))
        return 0
    except (LedgerError, OSError, KeyError, TypeError):
        # Do not echo malformed config or filesystem exception text: it may contain private paths or values.
        print("Ledger operation refused. Check private paths, documented config, budget/status, and request bounds.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
