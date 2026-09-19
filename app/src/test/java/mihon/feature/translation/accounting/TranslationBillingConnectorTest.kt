package mihon.feature.translation.accounting

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationBillingConnection
import tachiyomi.domain.translation.model.TranslationBillingRange
import tachiyomi.domain.translation.model.TranslationBillingSource

class TranslationBillingConnectorTest {
    @Test
    fun `Google dry run over the byte cap never executes a paid query`() {
        val requests = mutableListOf<Request>()
        val connector = connector {
            requests += it
            """{"totalBytesProcessed":"1025"}"""
        }
        val connection = TranslationBillingConnection(
            id = "google",
            label = "Billing",
            source = TranslationBillingSource.GOOGLE_BIGQUERY,
            credentialId = "billing-key",
            googleProject = "billing-project",
            googleTable = "billing-project.export.gcp_billing_export_v1_001",
            googleLocation = "US",
            maximumBytesBilled = 1024,
        )
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { connector.fetch(connection, TranslationBillingRange(1789084800000, 1789171200000)) }
        }
        assertTrue(failure.message!!.contains("byte cap"))
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(
            "https://bigquery.googleapis.com/bigquery/v2/projects/billing-project/queries",
            request.url.toString(),
        )
        val body = BillingJson.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("true", body.getValue("dryRun").jsonPrimitive.content)
        assertEquals("1024", body.getValue("maximumBytesBilled").jsonPrimitive.content)
        assertEquals("false", body.getValue("useLegacySql").jsonPrimitive.content)
        assertTrue(body.getValue("query").jsonPrimitive.content.startsWith("SELECT"))
    }

    @Test
    fun `OpenAI reads every usage page and keeps account cost currency separate from token totals`() = runBlocking {
        val requests = mutableListOf<Request>()
        val connector = connector {
            requests += it
            when {
                it.url.encodedPath.endsWith("/costs") ->
                    """{"data":[{"start_time":1789084800,"end_time":1789171200,"results":[{"amount":{"value":0.10,"currency":"usd"},"project_id":"proj_one"}]}],"has_more":false,"next_page":null}"""
                it.url.queryParameter("page") == "second" ->
                    """{"data":[{"start_time":1789084800,"end_time":1789171200,"results":[{"input_tokens":200,"output_tokens":20,"input_cached_tokens":0,"num_model_requests":2,"project_id":"proj_one","model":"model-two"}]}],"has_more":false,"next_page":null}"""
                else ->
                    """{"data":[{"start_time":1789084800,"end_time":1789171200,"results":[{"input_tokens":1000,"output_tokens":500,"input_cached_tokens":400,"num_model_requests":5,"project_id":"proj_one","model":"model-one"}]}],"has_more":true,"next_page":"second"}"""
            }
        }
        val connection =
            TranslationBillingConnection(
                "openai",
                "Organization",
                TranslationBillingSource.OPENAI_ORGANIZATION,
                credentialId = "billing-admin",
                organization = "org_test",
                projectIds = listOf("proj_one"),
            )
        val report = connector.fetch(connection, TranslationBillingRange(1789084800000, 1789171200000))
        assertEquals(3, requests.size)
        assertTrue(
            requests.all {
                it.method == "GET" && it.url.host == "api.openai.com" &&
                    it.url.queryParameter("end_time") == "1789171200"
            },
        )
        assertTrue(requests.all { it.header("OpenAI-Organization") == "org_test" })
        assertTrue(requests.all { it.url.queryParameter("project_ids") == "proj_one" })
        assertEquals(1200, report.usage.sumOf { it.inputTokens!! })
        assertEquals(520, report.usage.sumOf { it.outputTokens!! })
        assertEquals("USD", report.snapshots.single().currency)
        assertEquals("0.1", report.snapshots.single().amount)
        assertEquals(false, report.snapshots.single().invoice)
    }

    @Test
    fun `Google rejects out-of-range and duplicated cost buckets instead of reporting fabricated period totals`() {
        val connection = TranslationBillingConnection(
            "google",
            "Billing",
            TranslationBillingSource.GOOGLE_BIGQUERY,
            credentialId = "billing-key",
            googleProject = "billing-project",
            googleTable = "billing-project.export.costs",
            maximumBytesBilled = 1024,
        )
        fun row(day: String) = """{"f":[{"v":"$day"},{"v":"USD"},{"v":"project-one"},{"v":"0.90"}]}"""
        val valid = row("2026-09-11")
        fun fetch(rows: String) = connector { request ->
            val body = BillingJson.parseToJsonElement(
                Buffer().also {
                    request.body!!.writeTo(it)
                }.readUtf8(),
            ).jsonObject
            if (body["dryRun"]?.jsonPrimitive?.content == "true") {
                """{"totalBytesProcessed":"100"}"""
            } else {
                """{"jobComplete":true,"totalBytesProcessed":"100","schema":{"fields":[{"name":"day"},{"name":"currency"},{"name":"project_id"},{"name":"net_cost"}]},"rows":[$rows]}"""
            }
        }
        assertAll(
            {
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        fetch(
                            row("2030-01-01"),
                        ).fetch(connection, TranslationBillingRange(1789084800000, 1789171200000))
                    }
                }
            },
            {
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        fetch("$valid,$valid").fetch(connection, TranslationBillingRange(1789084800000, 1789171200000))
                    }
                }
            },
        )
    }

    @Test
    fun `Google reads bounded pages and rejects a returned project outside the configured filter`() = runBlocking {
        val connection = TranslationBillingConnection(
            "google",
            "Billing",
            TranslationBillingSource.GOOGLE_BIGQUERY,
            credentialId = "billing-key",
            googleProject = "billing-project",
            googleTable = "billing-project.export.costs",
            maximumBytesBilled = 1024,
            projectIds = listOf("project-one"),
        )
        val range = TranslationBillingRange(1789084800000, 1789171200000)
        val requests = mutableListOf<Request>()
        fun fixture(project: String) = connector { request ->
            requests += request
            if (request.method == "GET") {
                """{"jobComplete":true,"totalBytesBilled":"200","rows":[{"f":[{"v":"2026-09-11"},{"v":"SGD"},{"v":"project-one"},{"v":"-0.2"}]}]}"""
            } else {
                val body = BillingJson.parseToJsonElement(
                    Buffer().also {
                        request.body!!.writeTo(it)
                    }.readUtf8(),
                ).jsonObject
                if (body["dryRun"]?.jsonPrimitive?.content == "true") {
                    """{"totalBytesProcessed":"100"}"""
                } else {
                    assertEquals("1024", body["maximumBytesBilled"]?.jsonPrimitive?.content)
                    assertTrue(body.getValue("query").jsonPrimitive.content.contains("UNNEST(credits)"))
                    """{"jobComplete":true,"pageToken":"next-page","jobReference":{"projectId":"billing-project","jobId":"safe-job"},"schema":{"fields":[{"name":"day"},{"name":"currency"},{"name":"project_id"},{"name":"net_cost"}]},"rows":[{"f":[{"v":"2026-09-11"},{"v":"USD"},{"v":"$project"},{"v":"0.1"}]}]}"""
                }
            }
        }
        val report = fixture("project-one").fetch(connection, range)
        assertEquals(3, requests.size)
        assertEquals("next-page", requests.last().url.queryParameter("pageToken"))
        assertEquals("US", requests.last().url.queryParameter("location"))
        assertEquals(listOf("USD", "SGD"), report.snapshots.map { it.currency })
        assertEquals(listOf("0.1", "-0.2"), report.snapshots.map { it.amount })
        assertEquals(200L, report.bytesBilled)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture("outside-project").fetch(connection, range) }
        }
        Unit
    }

    private fun connector(handler: (Request) -> String) = TranslationBillingConnector(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                .body(handler(chain.request()).toResponseBody()).build()
        }.build(),
        authorize = { builder, _ -> builder.header("Authorization", "Bearer synthetic-billing-only") },
        now = { 1789257600000 },
    )
}
