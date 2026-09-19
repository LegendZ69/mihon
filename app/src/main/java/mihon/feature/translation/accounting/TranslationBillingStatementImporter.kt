package mihon.feature.translation.accounting

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import tachiyomi.domain.translation.model.TranslationBillingConnection
import tachiyomi.domain.translation.model.TranslationBillingReport
import tachiyomi.domain.translation.model.TranslationBillingSnapshot
import tachiyomi.domain.translation.model.TranslationBillingSource
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

internal data class ImportedBillingStatement(
    val connection: TranslationBillingConnection,
    val report: TranslationBillingReport,
)

/** Reads the documented Mihon statement format, never a credential or an inferred invoice amount. */
internal object TranslationBillingStatementImporter {
    fun parse(document: String, now: Long): ImportedBillingStatement {
        require(document.toByteArray().size <= MAX_BYTES) { "Statement JSON exceeds 64 KiB" }
        val json = runCatching { BillingJson.parseToJsonElement(document) as? JsonObject }.getOrNull()
            ?: error("Import a Mihon statement JSON document; PDF and provider-specific CSV formats are not parsed")
        require(
            json.keys ==
                setOf(
                    "version",
                    "provider",
                    "statement_id",
                    "scope",
                    "period_start",
                    "period_end_exclusive",
                    "currency",
                    "amount",
                    "invoice",
                ),
        ) {
            "Statement fields must match the version 1 format; credential fields and extra payloads are not accepted"
        }
        fun text(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("Statement is missing a required string field")
        require((json["version"] as? JsonPrimitive)?.intOrNull == 1) { "Unsupported statement version" }
        require(text("provider") == "Groq") { "This import accepts Groq statements" }
        val statementId = text("statement_id")
        require(statementId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "Enter a statement or invoice ID using letters, numbers, dots, underscores or hyphens"
        }
        val scope = text("scope").trim()
        require(scope.length in 1..160 && scope.none(Char::isISOControl)) {
            "Enter the account/project scope shown on the statement"
        }
        fun date(
            key: String,
        ) = runCatching { LocalDate.parse(text(key)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrElse { error("Statement dates must use YYYY-MM-DD") }
        val start = date("period_start")
        val end = date("period_end_exclusive")
        require(start >= 0 && end > start && end - start <= 366L * 86_400_000) {
            "Statement coverage must be between one day and one year"
        }
        val currency = text("currency").uppercase(Locale.ROOT)
        require(currency.matches(Regex("[A-Z]{3}"))) { "Enter the statement's three-letter currency code" }
        val amount = text("amount").takeIf { it.length <= 64 }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        require(amount != null && amount.precision() <= 38 && amount.scale() in -18..18) {
            "Enter a finite statement amount without a currency symbol"
        }
        val invoice =
            (json["invoice"] as? JsonPrimitive)?.booleanOrNull
                ?: error("Explicitly identify whether this is an invoice")
        val hash = MessageDigest.getInstance("SHA-256").digest("Groq\u0000$scope\u0000$statementId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val id = "statement-${hash.take(40)}"
        val connection =
            TranslationBillingConnection(
                id,
                "Groq ${if (invoice) "invoice" else "statement"} ${statementId.take(60)}",
                TranslationBillingSource.IMPORTED_STATEMENT,
            )
        val snapshot = TranslationBillingSnapshot(
            "$id-amount",
            id,
            TranslationBillingSource.IMPORTED_STATEMENT,
            "Groq $scope; statement $statementId",
            start,
            end,
            now,
            currency,
            amount.stripTrailingZeros().toPlainString(),
            invoice,
            sourceUrl = "https://console.groq.com/docs/billing-faqs",
            notes = "Explicit user-supplied statement amount; not independently authenticated by Mihon.",
        )
        return ImportedBillingStatement(
            connection,
            TranslationBillingReport(
                id,
                listOf(snapshot),
                syncedAt = now,
                periodStart = start,
                periodEnd = end,
                notes = "Imported user-supplied ${if (invoice) "invoice" else "statement"}; " +
                    "separate from app estimates and API-reported costs. No usage tokens are inferred.",
            ),
        )
    }

    const val MAX_BYTES = 65_536
}
