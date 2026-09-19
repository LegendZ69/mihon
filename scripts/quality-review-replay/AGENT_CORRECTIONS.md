# Reviewed controlled fixture corrections

`scripts/fixtures/translation-agent-reviewed-corrections.json` is a derived,
agent-authored expectation for four owned synthetic pages. Original image hashes,
historical asset hashes/revisions, exact old values, changes and ambiguity notes
are recorded separately. The original sampling assets and historical exports are
unchanged. These are prepared corrections, not a new provider response or an
approval of final app pixels.

The changes preserve original transcription and reading order:

- Restore “younger sister” while leaving its unstated possessor unspecified.
- Use the fixture-permitted “It's a promise.” without assigning a first-person
  subject to an ambiguous Korean utterance.
- Restore the authored dialogue/sign roles for three passages.
- Rotate physically sideways Japanese clockwise 90 degrees.
- Expand only the top edges of two mixed-page masks by 3.8 original pixels to
  contain independently measured dark source caps. The already repaired English
  warning is not changed.

## Bind a fresh saved baseline

Export the selected controlled chapter through the app first. Bind its current
saved revisions using a new private output path:

```sh
python3 scripts/translator_reviewed_corrections.py \
  --baseline /absolute/private/current-controlled-export.zip \
  --output /absolute/private/bound-reviewed-corrections.json
```

The generator reads only chapter results from the ZIP; it does not inspect
captures or credentials, mutate the app, or fabricate a saved revision. It accepts
one matching result per known original hash/dimensions and checks all patch target
preconditions. It then binds the entire baseline region list, not just changed
regions. A newer unrelated edit is preserved when explicitly rebinding; an edit
to a patch target is rejected for renewed assessment. Historical asset files may
also be used for host tests, but are not a fresh device export.

## Exercise the existing app repair and Undo

Start the existing localhost fixture server with the bound bundle, a new private
ledger and the already provisioned trusted TLS certificate/key:

```sh
python3 scripts/translator_fixture_server.py \
  --port 8765 --ledger /absolute/private/agent-review-ledger.jsonl \
  --cert /absolute/private/localhost-cert.pem \
  --key /absolute/private/localhost-key.pem \
  --review-corrections /absolute/private/bound-reviewed-corrections.json
```

Use the established device-to-host localhost transport and the existing custom
OpenAI fixture configuration (`mihon-fixture-only`, `mihon-fixture`). The
production app requires trusted HTTPS; do not disable TLS validation or change
production network policy. Select visual review for the four saved pages. This
is a bounded local fixture operation, with no cloud forwarding or paid provider
work. Non-review chapter generation, changed original bytes, stale revisions,
changed dimensions, or any intervening region edit are rejected.

The app's normal review coordinator validates and saves the proposed repair,
increments its revision, and retains the pre-repair result. Export after applying;
compare changes against the fixture and inspect actual inspector, reader and CBZ
pixels. Use **Undo AI repair** for the chosen page, then export again to verify
that the baseline content returns with a new revision. To apply the same reviewed
patch after Undo, bind that fresh export and restart the local server. Never edit
the bound revision number by hand or substitute a host-generated JSON for an
actual post-save export. Mixed applied/unapplied state cannot bind all four pages;
finish Undo for the selected set before rebinding the complete fixture.

The response is labeled `AUTHORED_AGENT_CORRECTION`. Its `visualComplete: true`
acknowledges coverage of the exact original images already reviewed by the agent;
the coordinator requires that protocol flag to apply visual changes. It does not
mean the final renderer was inspected or that a human or live model approved the
result. Keep this distinction in any acceptance receipt. The normal app review
UI may still use generic AI wording; the source of this particular response is
the explicitly configured deterministic localhost fixture.

Host checks:

```sh
python3 -m unittest scripts.tests.test_translator_reviewed_corrections \
  scripts.tests.test_translator_fixture_server
```

They cover original provenance, unchanged regions/source, minimal mask bounds,
physical rotation, omission of a forced possessor/subject, float32 mapping
tolerance, stale-input refusal and the actual HTTP review envelope/privacy
boundary. They do not establish on-device save, Undo, export or renderer results.
