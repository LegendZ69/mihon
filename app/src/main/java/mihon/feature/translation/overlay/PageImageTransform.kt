package mihon.feature.translation.overlay

/** Pixel mapping performed by the reader before an image reaches its view. */
enum class PageImageTransform {
    ORIGINAL,
    ROTATE_CLOCKWISE,
    ROTATE_COUNTERCLOCKWISE,
    LEFT_HALF,
    RIGHT_HALF,
    RIGHT_ABOVE_LEFT,
    LEFT_ABOVE_RIGHT,
    ;

    data class Piece(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val a: Float = 1f,
        val b: Float = 0f,
        val c: Float = 0f,
        val d: Float = 0f,
        val e: Float = 1f,
        val f: Float = 0f,
    ) {
        fun map(x: Float, y: Float): Pair<Float, Float> = (a * x + b * y + c) to (d * x + e * y + f)
    }

    data class Mapping(val width: Int, val height: Int, val pieces: List<Piece>)

    fun map(width: Int, height: Int): Mapping {
        require(width > 0 && height > 0)
        val w = width.toFloat()
        val h = height.toFloat()
        val half = width / 2
        val rightStart = width - half
        val left = Piece(0f, 0f, half.toFloat(), h)
        val right = Piece(rightStart.toFloat(), 0f, w, h, c = -rightStart.toFloat())
        return when (this) {
            ORIGINAL -> Mapping(width, height, listOf(Piece(0f, 0f, w, h)))
            ROTATE_CLOCKWISE -> Mapping(height, width, listOf(Piece(0f, 0f, w, h, 0f, -1f, h, 1f, 0f, 0f)))
            ROTATE_COUNTERCLOCKWISE -> Mapping(height, width, listOf(Piece(0f, 0f, w, h, 0f, 1f, 0f, -1f, 0f, w)))
            LEFT_HALF -> Mapping(half, height, listOf(left))
            RIGHT_HALF -> Mapping(half, height, listOf(right))
            RIGHT_ABOVE_LEFT -> Mapping(half, height * 2, listOf(right, left.copy(f = h)))
            LEFT_ABOVE_RIGHT -> Mapping(half, height * 2, listOf(left, right.copy(f = h)))
        }
    }
}
