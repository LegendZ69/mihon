# Translator cloud accounting

Cloud accounting appears separately from Mihon's per-request usage estimates. Organization totals can include other applications, and provider-reported costs are not invoices. Do not add these three views together.

## Google Cloud

Configure an existing standard or detailed Cloud Billing export as `project.dataset.table`, its BigQuery location, the project that will run queries, and a maximum billed-byte limit. Import a separate service-account JSON credential with access to that existing export and permission to run a query. The translator does not create tables, billing exports, IAM bindings or cloud commitments.

Each synchronization first submits a dry run. Missing byte estimates or an estimate above the configured cap prevent the query from running. The actual request also carries `maximumBytesBilled`; it uses bound date/project parameters and a bounded, read-only query. A timeout can leave a BigQuery job running: check the corresponding project before repeating an interrupted synchronization. The connector does not retry a query automatically.

Results retain their currencies and project scope. Costs include exported credits and adjustments, grouped by usage date across all exported services. Late export data can change a later synchronization. Usage-date accrual does not establish an invoice balance.

## Official OpenAI

Import a separate organization administration key. An inference credential is not reused. Optionally choose an organization and project IDs; a blank project filter uses the credential's account scope.

Synchronization reads organization completion usage and organization costs with independent pagination. Input/output/cache/request counters describe completion usage. Costs can cover other account services, so costs are not derived by multiplying these usage counters. Missing usage counters remain unavailable.

## Refresh and storage

Manual synchronization is the application default. Optional refresh intervals are at least one hour and remain subject to Android scheduling and connectivity. Saving settings does not synchronize an account. Authentication failures pause scheduled synchronization until credentials/access are corrected. Failed or interrupted synchronization retains the last completed report.

Billing credentials use separate Keystore-backed references. Connection settings and the latest account usage reports are private and excluded from Android backups. Reported cost snapshots are stored independently of app translation usage. A private write-ahead report journal records fetched data before SQL rows are applied, so interrupted local persistence can finish after restart without issuing another provider request. Deletion has a corresponding restart journal. The accounting view shows a loading or local recovery error until this state is reconciled. Removing a connection stops its scheduled work and removes its unreferenced billing credential; accounting history is retained until explicitly deleted. Deletion previews include the selected connection, exact cost-row count, serialized SQL bytes and cached report bytes; they exclude shared SQLite allocation. A changed report or storage summary invalidates the confirmation. Usage estimates, outstanding inference reservations and other connections are retained.

The UI limits each selected date range to 31 days. A connector also bounds pages, rows, response bytes and total runtime. Oversized, malformed or unexpectedly repeated results fail visibly instead of inventing totals.

## Groq statement import

Groq's ordinary-account billing API is unavailable. Open the Groq console to retrieve an invoice or statement, then import its explicitly supplied amount using Mihon's JSON format below. This is a local format, not a claimed Groq API response. The app does not extract or infer amounts from PDFs, images or undocumented provider-specific CSV columns.

```json
{
  "version": 1,
  "provider": "Groq",
  "statement_id": "replace-with-statement-id",
  "scope": "replace-with-account-or-project-scope",
  "period_start": "2026-09-01",
  "period_end_exclusive": "2026-10-01",
  "currency": "USD",
  "amount": "0.00",
  "invoice": true
}
```

Every value in this example must be replaced or checked against the actual document; `0.00` is a format example, not a measured account amount. Set `invoice` to `true` only for an invoice, otherwise `false`. Amounts may be negative for credits. Date coverage is inclusive at the start and exclusive at the end, from one day up to one year. Imports retain the original currency; no conversion or token estimate is fabricated.

Only the nine documented fields are accepted, with a 64 KiB document bound. Credentials and arbitrary extra payload fields are rejected. Importing the same statement ID and account scope again cannot add duplicate cost. A conflicting amount or period preserves the existing record and requires an explicit accounting-history decision. Imported amounts are labelled user supplied and are not independently authenticated by Mihon.

## Sources

REST fields and behavior were checked on 2026-09-12:

- [Cloud Billing export](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery), [standard export schema](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-tables/standard-usage).
- [BigQuery query REST API](https://docs.cloud.google.com/bigquery/docs/reference/rest/v2/jobs/query), [query cost controls](https://docs.cloud.google.com/bigquery/docs/best-practices-costs).
- [OpenAI organization completion usage](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/completions), [organization costs](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs).
- [Groq billing](https://console.groq.com/docs/billing-faqs), [enterprise metrics](https://console.groq.com/docs/prometheus-metrics). The ordinary-account connector has no documented invoice synchronization endpoint; enterprise metrics do not establish invoice amounts.

Offline request and parser regressions do not prove that a particular cloud account is configured correctly. Live validation requires separately configured credentials and remains a distinct acceptance step.

## Dashboard measurement scope

The application dashboard reads a consistent database snapshot in 256-row pages, rather than retaining or truncating the history to a recent-record list. Time/provider/model/job filters apply to the full matching history. Provider/model breakdowns are paginated independently of the totals. Operation timestamps describe their last durable update; request usage keeps its original dispatch timestamp. Saved-page operation counts and distinct job/page identities are reported separately.

Operation attribution is pinned when new work is first saved. Explicit review settings take precedence over a chapter's provider; later settings changes do not relabel earlier operations. Historical records with absent attribution remain unavailable and are excluded only when an attribution filter requires it. Account billing remains separate from this local usage view.

Token and money sums use exact integers/decimals. Missing categories retain their unavailable counts; an empty set of measured values is not presented as a measured zero. Latency percentiles are labelled intervals from fixed histogram bounds, not exact percentile values. Underlying request-usage pages retain pricing and exchange-rate provenance.

SQLite JSON extraction used for filtered distinct-page counts follows the [SQLite JSON API](https://www.sqlite.org/json1.html); the app uses its bundled SQLite runtime. Host aggregation tests do not replace actual SQLite/16 KB and handset validation.
