package mihon.feature.translation.accounting

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.model.TranslationAccountUsageBucket
import tachiyomi.domain.translation.model.TranslationBillingConnection
import tachiyomi.domain.translation.model.TranslationBillingRange
import tachiyomi.domain.translation.model.TranslationBillingReport
import tachiyomi.domain.translation.model.TranslationBillingSnapshot
import tachiyomi.domain.translation.model.TranslationBillingSource
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.service.TranslationBillingSourceAdapter
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

internal val BillingJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** A fixed-origin, read-only cloud accounting boundary; query charges require a configured byte cap. */
internal class TranslationBillingConnector(
    private val client: OkHttpClient,
    private val authorize: suspend (Request.Builder, TranslationBillingConnection) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : TranslationBillingSourceAdapter {
    override suspend fun fetch(
        connection: TranslationBillingConnection,
        range: TranslationBillingRange,
    ): TranslationBillingReport {
        connection.validate()
        range.validate()
        return withTimeout(180_000) {
            when (connection.source) {
                TranslationBillingSource.GOOGLE_BIGQUERY -> google(connection, range)
                TranslationBillingSource.OPENAI_ORGANIZATION -> openAi(connection, range)
                TranslationBillingSource.IMPORTED_STATEMENT -> error(
                    "Statement connections have no synchronization API; import a statement explicitly",
                )
            }
        }
    }

    private suspend fun openAi(
        connection: TranslationBillingConnection,
        range: TranslationBillingRange,
    ): TranslationBillingReport {
        val usage = mutableListOf<TranslationAccountUsageBucket>()
        val snapshots = mutableListOf<TranslationBillingSnapshot>()
        val usageIds = mutableSetOf<String>()
        val costIds = mutableSetOf<String>()
        val synced = now()
        val organization = connection.organization.ifBlank { "credential organization" }
        suspend fun read(path: String, cost: Boolean) {
            var cursor: String? = null
            val cursors = mutableSetOf<String>()
            var pageCount = 0
            do {
                check(++pageCount <= 100) { "OpenAI accounting exceeds the page limit; reduce the scope" }
                val url = "https://api.openai.com/v1/organization/$path".toHttpUrl().newBuilder()
                    .addQueryParameter("start_time", (range.startMillis / 1000).toString())
                    .addQueryParameter("end_time", (range.endMillis / 1000).toString())
                    .addQueryParameter("bucket_width", "1d").addQueryParameter("limit", "31")
                    .addQueryParameter("group_by", "project_id")
                    .apply {
                        if (!cost) addQueryParameter("group_by", "model")
                        connection.projectIds.forEach { addQueryParameter("project_ids", it) }
                        cursor?.let { addQueryParameter("page", it) }
                    }.build()
                val response = exchange(connection, Request.Builder().url(url))
                val buckets =
                    response["data"] as? JsonArray
                        ?: error("OpenAI omitted accounting buckets; earlier reports are preserved")
                for (element in buckets) {
                    val bucket = element as? JsonObject ?: error("Invalid OpenAI accounting bucket")
                    val start = (bucket["start_time"] as? JsonPrimitive)?.longOrNull?.takeIf {
                        it in
                            0..Long.MAX_VALUE / 1000
                    }?.times(1000)
                    val end = (bucket["end_time"] as? JsonPrimitive)?.longOrNull?.takeIf {
                        it in
                            0..Long.MAX_VALUE / 1000
                    }?.times(1000)
                    check(
                        start != null && end != null && end > start && end - start <= DAY && start < range.endMillis &&
                            end > range.startMillis,
                    ) {
                        "OpenAI returned an invalid or out-of-range accounting period"
                    }
                    val results = bucket["results"] as? JsonArray ?: error("OpenAI omitted bucket results")
                    for (value in results) {
                        val row = value as? JsonObject ?: error("Invalid OpenAI accounting result")
                        val project = (row["project_id"] as? JsonPrimitive)?.contentOrNull
                        check(
                            project == null ||
                                (
                                    project.length <= 128 &&
                                        (connection.projectIds.isEmpty() || project in connection.projectIds)
                                    ),
                        ) {
                            "OpenAI returned accounting outside the configured project scope"
                        }
                        val scope = "OpenAI $organization; project ${project ?: "unallocated"}; all account services"
                        if (cost) {
                            val amount =
                                row["amount"] as? JsonObject
                                    ?: error("OpenAI omitted a reported amount; costs are unavailable")
                            val currency = currency((amount["currency"] as? JsonPrimitive)?.contentOrNull)
                            val id = identity(connection.id, start.toString(), currency, scope)
                            check(costIds.add(id)) { "OpenAI repeated a cost bucket; accounting was not applied" }
                            snapshots +=
                                TranslationBillingSnapshot(
                                    id, connection.id, TranslationBillingSource.OPENAI_ORGANIZATION,
                                    scope, maxOf(start, range.startMillis), minOf(end, range.endMillis), synced,
                                    currency,
                                    decimal(
                                        (amount["value"] as? JsonPrimitive)?.contentOrNull,
                                    ),
                                    sourceUrl = OPENAI_COST_SOURCE,
                                    notes =
                                    "Provider-reported account costs, not an invoice total or application estimate. " +
                                        "Cloud data can arrive late.",
                                )
                        } else {
                            val model = (row["model"] as? JsonPrimitive)?.contentOrNull
                            check(model == null || model.length <= 256) { "Invalid account usage model" }
                            check(usageIds.add(identity(start.toString(), project.orEmpty(), model.orEmpty()))) {
                                "OpenAI repeated a usage bucket; accounting was not applied"
                            }
                            fun count(key: String) = (row[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                            val input = count("input_tokens")
                            usage +=
                                TranslationAccountUsageBucket(
                                    maxOf(start, range.startMillis),
                                    minOf(end, range.endMillis),
                                    project,
                                    model,
                                    inputTokens = input,
                                    outputTokens = count("output_tokens"),
                                    cachedInputTokens = count("input_cached_tokens")?.takeIf {
                                        input == null || it <= input
                                    },
                                    requests = count("num_model_requests"),
                                )
                        }
                        check(usage.size + snapshots.size <= 20_000) {
                            "OpenAI accounting exceeds the row limit; reduce the scope"
                        }
                    }
                }
                val more =
                    (response["has_more"] as? JsonPrimitive)?.booleanOrNull
                        ?: error("OpenAI omitted its accounting pagination state")
                cursor = if (more) {
                    (response["next_page"] as? JsonPrimitive)?.contentOrNull?.takeIf {
                        it.isNotBlank() &&
                            it.length <= 4096
                    }
                        ?: error("OpenAI accounting is incomplete; missing next-page cursor")
                } else {
                    null
                }
                if (cursor != null) check(cursors.add(cursor)) { "OpenAI repeated an accounting page cursor" }
            } while (cursor != null)
        }
        read("usage/completions", cost = false)
        read("costs", cost = true)
        return TranslationBillingReport(
            connection.id,
            snapshots,
            usage,
            synced,
            range.startMillis,
            range.endMillis,
            notes = "Completion usage is separate from reported costs across all account services. " +
                "Account scope can include activity outside Mihon; " +
                "these totals are not added to app estimates or invoice amounts.",
        )
    }

    private suspend fun google(
        connection: TranslationBillingConnection,
        range: TranslationBillingRange,
    ): TranslationBillingReport {
        val cap = checkNotNull(connection.maximumBytesBilled)
        val url = "https://bigquery.googleapis.com/bigquery/v2/projects/${connection.googleProject}/queries"
        val requestId = UUID.randomUUID().toString()
        val query = googleQuery(connection, range, requestId)
        val dry = exchange(
            connection,
            Request.Builder().url(url).post(
                JsonObject(query + ("dryRun" to JsonPrimitive(true))).toString().toRequestBody(JSON),
            ),
        )
        val expectedBytes = dry["totalBytesProcessed"]?.jsonPrimitive?.longOrNull
            ?: error("BigQuery dry run did not report estimated bytes; no query was executed")
        check(expectedBytes in 0..cap) {
            "BigQuery dry run exceeds the configured byte cap; no paid query was executed"
        }
        var result = exchange(connection, Request.Builder().url(url).post(query.toString().toRequestBody(JSON)))
        val rows = mutableListOf<JsonObject>()
        var schema: List<String>? = null
        var processed: Long? = null
        var billed: Long? = null
        val pages = mutableSetOf<String>()
        var polls = 0
        while (true) {
            check((result["errors"] as? JsonArray).isNullOrEmpty()) {
                "BigQuery query reported errors; earlier accounting is preserved"
            }
            result["schema"]?.jsonObject?.get("fields")?.jsonArray?.let { fields ->
                val names = fields.map { it.jsonObject.getValue("name").jsonPrimitive.content }
                check(names.containsAll(listOf("day", "currency", "project_id", "net_cost"))) {
                    "Unexpected billing export query schema"
                }
                schema = names
            }
            processed = result["totalBytesProcessed"]?.jsonPrimitive?.longOrNull ?: processed
            billed = result["totalBytesBilled"]?.jsonPrimitive?.longOrNull ?: billed
            (result["rows"] as? JsonArray)?.forEach { rows += it.jsonObject }
            check(rows.size <= 20_000) { "Billing query exceeds the row limit; reduce the project/date scope" }
            val complete = result["jobComplete"]?.jsonPrimitive?.booleanOrNull == true
            val next = result["pageToken"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
            if (complete && next == null) break
            check(++polls <= 100) {
                "Billing query is incomplete; earlier accounting is preserved. " +
                    "Check the BigQuery job before syncing again."
            }
            if (next != null) check(pages.add(next)) { "BigQuery repeated a page cursor; accounting was not applied" }
            val reference = result["jobReference"]?.jsonObject ?: error("BigQuery omitted its unfinished job reference")
            val project = reference["projectId"]?.jsonPrimitive?.contentOrNull
            val job = reference["jobId"]?.jsonPrimitive?.contentOrNull
            check(project == connection.googleProject && !job.isNullOrBlank()) {
                "BigQuery returned an unexpected job reference"
            }
            val nextUrl = "https://bigquery.googleapis.com/bigquery/v2/projects/${connection.googleProject}/queries"
                .toHttpUrl()
                .newBuilder()
                .addPathSegment(job).addQueryParameter("location", connection.googleLocation)
                .addQueryParameter("maxResults", "1000").addQueryParameter("timeoutMs", "10000")
                .apply { next?.let { addQueryParameter("pageToken", it) } }.build()
            if (!complete) delay(250)
            result = exchange(connection, Request.Builder().url(nextUrl))
        }
        val names = schema ?: if (rows.isEmpty()) emptyList() else error("BigQuery omitted its result schema")
        val synced = now()
        val snapshots = rows.map { row ->
            val values = row.getValue("f").jsonArray.map { it.jsonObject["v"]?.jsonPrimitive?.contentOrNull }
            check(values.size == names.size) { "Billing query returned incomplete columns" }
            val fields = names.zip(values).toMap()
            val day = runCatching { LocalDate.parse(fields["day"]) }.getOrElse { error("Invalid billing date") }
            val start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            check(start < range.endMillis && start + DAY > range.startMillis) {
                "BigQuery returned a cost bucket outside the requested period"
            }
            val currency = currency(fields["currency"])
            val project = fields["project_id"].orEmpty()
            check(project.length <= 128 && (connection.projectIds.isEmpty() || project in connection.projectIds)) {
                "BigQuery returned accounting outside the configured project scope"
            }
            val scope = "Google export ${connection.googleTable}; project ${project.ifBlank {
                "unallocated"
            }}; all exported services"
            TranslationBillingSnapshot(
                identity(
                    connection.id,
                    start.toString(),
                    currency,
                    scope,
                ),
                connection.id, TranslationBillingSource.GOOGLE_BIGQUERY,
                scope, maxOf(start, range.startMillis), minOf(start + DAY, range.endMillis), synced,
                currency, decimal(fields["net_cost"]), sourceUrl = GOOGLE_SOURCE,
                notes = "Usage-date accrual including exported credits and adjustments. " +
                    "Billing exports can arrive late; this is not an invoice balance.",
            )
        }
        check(
            snapshots.map {
                it.id
            }.distinct().size == snapshots.size,
        ) { "BigQuery repeated a cost bucket; accounting was not applied" }
        return TranslationBillingReport(
            connection.id,
            snapshots,
            syncedAt = synced,
            periodStart = range.startMillis,
            periodEnd = range.endMillis,
            bytesProcessed = processed ?: expectedBytes,
            bytesBilled = billed,
            notes = "Read from the existing billing export; currency and account/project scope are preserved. " +
                "No invoice reconciliation is implied.",
        )
    }

    private fun googleQuery(
        connection: TranslationBillingConnection,
        range: TranslationBillingRange,
        requestId: String,
    ): JsonObject {
        val sql = """
            SELECT CAST(DATE(usage_start_time) AS STRING) AS day, currency,
              IFNULL(project.id, '') AS project_id,
              CAST(SUM(CAST(cost AS NUMERIC) +
                IFNULL((SELECT SUM(CAST(c.amount AS NUMERIC)) FROM UNNEST(credits) c), 0)) AS STRING) AS net_cost
            FROM `${connection.googleTable}`
            WHERE usage_start_time >= TIMESTAMP_MILLIS(@start_ms) AND usage_start_time < TIMESTAMP_MILLIS(@end_ms)
              ${if (connection.projectIds.isEmpty()) "" else "AND project.id IN UNNEST(@projects)"}
            GROUP BY day, currency, project_id ORDER BY day, currency, project_id
        """.trimIndent()
        fun parameter(name: String, value: Long) = buildJsonObject {
            put("name", name)
            put("parameterType", buildJsonObject { put("type", "INT64") })
            put("parameterValue", buildJsonObject { put("value", value.toString()) })
        }
        return buildJsonObject {
            put("query", sql)
            put("useLegacySql", false)
            put("dryRun", false)
            put("useQueryCache", true)
            put("maximumBytesBilled", checkNotNull(connection.maximumBytesBilled).toString())
            put("requestId", requestId)
            put("location", connection.googleLocation)
            put("timeoutMs", 10000)
            put("maxResults", 1000)
            put("jobTimeoutMs", "120000")
            put("parameterMode", "NAMED")
            put(
                "queryParameters",
                buildJsonArray {
                    add(parameter("start_ms", range.startMillis))
                    add(parameter("end_ms", range.endMillis))
                    if (connection.projectIds.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("name", "projects")
                                put(
                                    "parameterType",
                                    buildJsonObject {
                                        put("type", "ARRAY")
                                        put("arrayType", buildJsonObject { put("type", "STRING") })
                                    },
                                )
                                put(
                                    "parameterValue",
                                    buildJsonObject {
                                        put(
                                            "arrayValues",
                                            buildJsonArray {
                                                connection.projectIds.forEach {
                                                    add(buildJsonObject { put("value", it) })
                                                }
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun exchange(connection: TranslationBillingConnection, builder: Request.Builder): JsonObject {
        authorize(builder, connection)
        if (connection.source == TranslationBillingSource.OPENAI_ORGANIZATION && connection.organization.isNotBlank()) {
            builder.header("OpenAI-Organization", connection.organization)
        }
        val response = client.newCall(builder.header("Accept", "application/json").build()).await()
        return response.use {
            if (!it.isSuccessful) {
                throw TranslationException(
                    if (it.code in
                        setOf(401, 403)
                    ) {
                        TranslationFailureKind.AUTHENTICATION
                    } else if (it.code ==
                        429
                    ) {
                        TranslationFailureKind.RATE_LIMIT
                    } else {
                        TranslationFailureKind.TRANSIENT
                    },
                    "Accounting endpoint returned HTTP ${it.code}; earlier reports are preserved",
                    httpStatus = it.code,
                )
            }
            val source = it.body.source()
            check(!source.request(4L * 1024 * 1024 + 1)) {
                "Accounting response exceeds the 4 MiB safety bound; reduce the scope"
            }
            try {
                BillingJson.parseToJsonElement(source.readUtf8()).jsonObject
            } catch (
                _: Exception,
            ) {
                error("Accounting endpoint returned malformed JSON; earlier reports are preserved")
            }
        }
    }

    private fun decimal(value: String?): String {
        val amount = value?.let { runCatching { BigDecimal(it) }.getOrNull() }
        check(amount != null && amount.precision() <= 38 && amount.scale() in -18..18) {
            "Accounting endpoint returned an invalid amount"
        }
        return amount.stripTrailingZeros().toPlainString()
    }

    private fun currency(value: String?): String {
        val normalized = value?.uppercase(Locale.ROOT)
        check(normalized != null && normalized.matches(Regex("[A-Z]{3}"))) {
            "Accounting endpoint omitted a valid currency"
        }
        return normalized
    }

    private fun identity(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val DAY = 86_400_000L
        private const val GOOGLE_SOURCE = "https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery"
        private const val OPENAI_COST_SOURCE = "https://developers.openai.com/api/reference/resources/admin/" +
            "subresources/organization/subresources/usage/methods/costs"
    }
}
