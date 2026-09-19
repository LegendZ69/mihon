package mihon.feature.translation.accounting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationBillingSource

class TranslationBillingStatementTest {
    @Test
    fun `Groq invoice import preserves currency period and identity without credentials or token estimates`() {
        val document = """{
            "version":1,"provider":"Groq","statement_id":"invoice-September-01","scope":"account-example",
            "period_start":"2026-09-01","period_end_exclusive":"2026-10-01",
            "currency":"sgd","amount":"12.3400","invoice":true
        }"""
        val first = TranslationBillingStatementImporter.parse(document, 1000)
        val repeated = TranslationBillingStatementImporter.parse(document, 2000)
        first.connection.validate()
        assertEquals(TranslationBillingSource.IMPORTED_STATEMENT, first.connection.source)
        assertEquals("", first.connection.credentialId)
        assertEquals(null, first.connection.refreshMinutes)
        assertEquals(first.connection.id, repeated.connection.id)
        assertEquals(first.report.snapshots.single().id, repeated.report.snapshots.single().id)
        assertEquals("SGD", first.report.snapshots.single().currency)
        assertEquals("12.34", first.report.snapshots.single().amount)
        assertTrue(first.report.snapshots.single().invoice)
        assertEquals(1788220800000, first.report.periodStart)
        assertEquals(1790812800000, first.report.periodEnd)
        assertTrue(first.report.usage.isEmpty())
        assertTrue(first.report.notes.contains("user-supplied"))
    }
}
