package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

@Serializable
enum class StructuredImportFormat { PORTABLE_V1, PROVIDER_PAGES, GEMINI, OPENAI_RESPONSES, CHAT_COMPLETIONS, MIHON_ZIP }

@Serializable
enum class StructuredImportCoordinates { ORIGINAL_PIXELS, NORMALIZED_1000 }

@Serializable
data class StructuredImportSource(
    val name: String,
    val sha256: String,
    val bytes: Long,
    val format: StructuredImportFormat,
)

@Serializable
data class StructuredImportIssue(val path: String, val message: String)

/** No filesystem paths, provider credentials or resumable work can be represented by this descriptor. */
@Serializable
data class StructuredImportTransform(
    val originalWidth: Int,
    val originalHeight: Int,
    val originalImageHash: String,
    val left: Int,
    val top: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
)

@Serializable
data class StructuredImportRegion(
    val id: String,
    val translatedText: String,
    val points: List<TranslationPoint>? = null,
    val sourceText: String? = null,
    val correctedText: String? = null,
    val type: String = "dialogue",
    val readingOrder: Int = 0,
    val rotation: Float = 0f,
    val included: Boolean = true,
    val ignoredReason: String? = null,
    val aiConfidence: Float? = null,
)

@Serializable
data class StructuredImportPage(
    val key: String,
    val imageId: String,
    val imageHash: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val coordinates: StructuredImportCoordinates,
    val regions: List<StructuredImportRegion>,
    val detectedLanguage: String? = null,
    val transform: StructuredImportTransform? = null,
    val issues: List<StructuredImportIssue> = emptyList(),
)

@Serializable
data class StructuredImportDocument(
    val source: StructuredImportSource,
    val pages: List<StructuredImportPage>,
    val errors: List<StructuredImportIssue> = emptyList(),
    /** Provider-reported values are historical provenance, never newly incurred application usage. */
    val externalUsageJson: String? = null,
)

@Serializable
data class StructuredImportProvenance(
    val source: StructuredImportSource,
    val sourcePageKey: String,
    val sourceImageId: String,
    val importedAt: Long,
    val externalUsageJson: String? = null,
)

enum class StructuredImportDisposition { READY, IDENTICAL, CONFLICT, UNMAPPED, INVALID }

/** expectedRevision=null means the commit must still find no result for this exact original identity. */
data class StructuredImportPlannedPage(
    val sourceKey: String,
    val image: TranslationImage?,
    val result: TranslationPageResult?,
    val expectedRevision: Long?,
    val disposition: StructuredImportDisposition,
    val issues: List<StructuredImportIssue> = emptyList(),
    val normalizedRegions: Set<String> = emptySet(),
)

data class StructuredImportPlan(
    val document: StructuredImportDocument,
    val pages: List<StructuredImportPlannedPage>,
) {
    val readyPages: List<StructuredImportPlannedPage> get() = pages.filter {
        it.disposition == StructuredImportDisposition.READY
    }
}
