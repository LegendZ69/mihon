package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.GeometryIssueCode
import tachiyomi.domain.translation.model.PolygonNormalizationKind
import tachiyomi.domain.translation.model.PolygonNormalizationOutcome
import tachiyomi.domain.translation.model.PolygonNormalizationStatus
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.service.TranslationGeometry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class TranslationGeometryTest {
    private val square = listOf(
        TranslationPoint(0f, 0f),
        TranslationPoint(10f, 0f),
        TranslationPoint(10f, 10f),
        TranslationPoint(0f, 10f),
    )

    @Test
    fun `signed zero is the same closing coordinate and preserves the original first vertex`() {
        val outcome = TranslationGeometry.normalizeAndValidate(square + TranslationPoint(-0f, 0f), 100, 100)
        outcome.status shouldBe PolygonNormalizationStatus.NORMALIZED
        outcome.points shouldBe square
        outcome.sourceVertexIndices shouldBe listOf(0, 1, 2, 3)
        outcome.changes.single().kind shouldBe PolygonNormalizationKind.REMOVED_CLOSING_VERTEX
        outcome.changes.single().originalVertexIndex shouldBe 4
    }

    @Test
    fun `valid reversed winding and near collinear corners remain unchanged`() {
        val almostStraight = listOf(
            TranslationPoint(0f, 0.001f),
            TranslationPoint(5f, 0f),
            TranslationPoint(10f, 0.001f),
            TranslationPoint(10f, 10f),
            TranslationPoint(0f, 10f),
        )
        listOf(square, square.reversed(), almostStraight).forEach { points ->
            val outcome = TranslationGeometry.normalizeAndValidate(points, 100, 100)
            outcome.status shouldBe PolygonNormalizationStatus.UNCHANGED
            outcome.points shouldBe points
            outcome.changes shouldBe emptyList()
        }
    }

    @Test
    fun `crossed concave repeated and zero area outlines retain their rejection reasons`() {
        val cases = listOf(
            listOf(square[0], square[2], square[1], square[3]) to GeometryIssueCode.SELF_INTERSECTION,
            listOf(square[0], square[1], TranslationPoint(5f, 5f), square[2], square[3]) to GeometryIssueCode.CONCAVE,
            listOf(square[0], square[1], square[2], square[1], square[3]) to GeometryIssueCode.REPEATED_VERTEX,
            listOf(TranslationPoint(0f, 0f), TranslationPoint(5f, 5f), TranslationPoint(10f, 10f)) to
                GeometryIssueCode.ZERO_AREA,
        )
        cases.forEach { (points, code) ->
            val outcome = TranslationGeometry.normalizeAndValidate(points, 100, 100)
            outcome.status shouldBe PolygonNormalizationStatus.REJECTED
            outcome.issues.any { it.code == code } shouldBe true
            outcome.points shouldBe points
            outcome.changes shouldBe emptyList()
        }
    }

    @Test
    fun `malformed coordinates and dimensions are rejected before redundant vertices can disappear`() {
        listOf(
            Float.NaN to GeometryIssueCode.NONFINITE_COORDINATE,
            Float.POSITIVE_INFINITY to GeometryIssueCode.NONFINITE_COORDINATE,
            -1f to GeometryIssueCode.OUT_OF_BOUNDS,
            101f to GeometryIssueCode.OUT_OF_BOUNDS,
        ).forEach { (x, code) ->
            val points = listOf(TranslationPoint(x, 0f)) + square.drop(1) + TranslationPoint(x, 0f)
            val outcome = TranslationGeometry.normalizeAndValidate(points, 100, 100)
            outcome.status shouldBe PolygonNormalizationStatus.REJECTED
            outcome.changes shouldBe emptyList()
            outcome.issues.filter { it.code == code }.flatMap { it.vertexIndices } shouldBe listOf(0, 4)
        }
        val invalidDimensions = TranslationGeometry.normalizeAndValidate(square, 0, 100)
        invalidDimensions.issues.first().code shouldBe GeometryIssueCode.INVALID_DIMENSIONS
    }

    @Test
    fun `canonical corner limit follows cleanup while input work remains bounded`() {
        fun regularPolygon(count: Int) = List(count) { index ->
            val angle = 2 * PI * index / count
            TranslationPoint((50 + 40 * cos(angle)).toFloat(), (50 + 40 * sin(angle)).toFloat())
        }
        val corners = regularPolygon(32)
        val closed = TranslationGeometry.normalizeAndValidate(corners + corners.first(), 100, 100)
        closed.status shouldBe PolygonNormalizationStatus.NORMALIZED
        closed.points shouldBe corners
        closed.changes.single().originalVertexIndex shouldBe 32

        val tooManyCorners = TranslationGeometry.normalizeAndValidate(regularPolygon(33), 100, 100)
        tooManyCorners.status shouldBe PolygonNormalizationStatus.REJECTED
        tooManyCorners.issues.single().code shouldBe GeometryIssueCode.VERTEX_COUNT

        val tooManyInputs = TranslationGeometry.normalizeAndValidate(List(257) { square[0] }, 100, 100)
        tooManyInputs.status shouldBe PolygonNormalizationStatus.REJECTED
        tooManyInputs.issues.single().code shouldBe GeometryIssueCode.VERTEX_COUNT
        tooManyInputs.changes shouldBe emptyList()
    }

    @Test
    fun `normalization metadata round trips without losing original vertex references`() {
        val outcome = TranslationGeometry.normalizeAndValidate(square + square.first(), 100, 100)
        Json.decodeFromString<PolygonNormalizationOutcome>(Json.encodeToString(outcome)) shouldBe outcome
        val crossed = TranslationGeometry.normalizeAndValidate(
            listOf(square[0], square[2], square[1], square[3]),
            100,
            100,
        )
        Json.decodeFromString<PolygonNormalizationOutcome>(Json.encodeToString(crossed)) shouldBe crossed
        crossed.issues.first { it.code == GeometryIssueCode.SELF_INTERSECTION }.vertexIndices shouldBe
            listOf(0, 1, 2, 3)
    }

    @Test
    fun `backtracking remains rejected with precise original vertices rather than being pruned`() {
        val retraced = listOf(
            TranslationPoint(0f, 0f),
            TranslationPoint(10f, 0f),
            TranslationPoint(5f, 0f),
            TranslationPoint(10f, 10f),
            TranslationPoint(0f, 10f),
        )

        val outcome = TranslationGeometry.normalizeAndValidate(retraced, 100, 100)

        outcome.status shouldBe PolygonNormalizationStatus.REJECTED
        outcome.points shouldBe retraced
        outcome.changes shouldBe emptyList()
        outcome.issues.single { it.code == GeometryIssueCode.BACKTRACKING }.vertexIndices shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `redundant boundary vertices preserve the outline and original vertex identities`() {
        val outcome = TranslationGeometry.normalizeAndValidate(
            listOf(
                TranslationPoint(10f, 10f),
                TranslationPoint(20f, 10f),
                TranslationPoint(20f, 10f),
                TranslationPoint(30f, 10f),
                TranslationPoint(30f, 30f),
                TranslationPoint(10f, 30f),
                TranslationPoint(10f, 10f),
            ),
            100,
            100,
        )

        outcome.status shouldBe PolygonNormalizationStatus.NORMALIZED
        outcome.points shouldBe listOf(
            TranslationPoint(10f, 10f),
            TranslationPoint(30f, 10f),
            TranslationPoint(30f, 30f),
            TranslationPoint(10f, 30f),
        )
        outcome.sourceVertexIndices shouldBe listOf(0, 3, 4, 5)
        outcome.changes.associate { it.originalVertexIndex to it.kind } shouldBe mapOf(
            1 to PolygonNormalizationKind.REMOVED_COLLINEAR_VERTEX,
            2 to PolygonNormalizationKind.REMOVED_CONSECUTIVE_DUPLICATE,
            6 to PolygonNormalizationKind.REMOVED_CLOSING_VERTEX,
        )
        outcome.issues shouldBe emptyList()
    }
}
