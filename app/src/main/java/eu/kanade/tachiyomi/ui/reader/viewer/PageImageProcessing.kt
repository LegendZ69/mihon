package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.feature.translation.overlay.PageImageTransform
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil

/**
 * The image of [page] as a pager shows it: a wide page rotated to fit or split in two, the half on the reading
 * side first, so [isLeftToRight] tells which. [onPageSplit] is told of the [InsertPage] for the second half.
 * [onTransform] is told how the result's pixels relate to the original image, for translation overlays.
 */
fun DualPageSettings.processPagedImage(
    page: ReaderPage,
    imageSource: BufferedSource,
    isLeftToRight: Boolean,
    onTransform: (PageImageTransform) -> Unit = {},
    onPageSplit: (InsertPage) -> Unit,
): BufferedSource {
    onTransform(PageImageTransform.ORIGINAL)
    if (dualPageRotateToFit) {
        return rotateDualPage(imageSource, onTransform)
    }

    if (!dualPageSplit) {
        return imageSource
    }

    if (page is InsertPage) {
        return splitInHalf(imageSource, page, isLeftToRight, onTransform)
    }

    val isDoublePage = ImageUtil.isWideImage(imageSource)
    if (!isDoublePage) {
        return imageSource
    }

    onPageSplit(InsertPage(page))

    return splitInHalf(imageSource, page, isLeftToRight, onTransform)
}

/**
 * The image of a page as a strip shows it: a wide page rotated to fit, or split with its halves one above the other.
 */
fun DualPageSettings.processStripImage(
    imageSource: BufferedSource,
    onTransform: (PageImageTransform) -> Unit = {},
): BufferedSource {
    onTransform(PageImageTransform.ORIGINAL)
    if (dualPageRotateToFit) {
        return rotateDualPage(imageSource, onTransform)
    }

    if (dualPageSplit) {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (isDoublePage) {
            val upperSide = if (dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
            onTransform(
                if (upperSide == ImageUtil.Side.RIGHT) {
                    PageImageTransform.RIGHT_ABOVE_LEFT
                } else {
                    PageImageTransform.LEFT_ABOVE_RIGHT
                },
            )
            return ImageUtil.splitAndMerge(imageSource, upperSide)
        }
    }

    return imageSource
}

private fun DualPageSettings.rotateDualPage(
    imageSource: BufferedSource,
    onTransform: (PageImageTransform) -> Unit,
): BufferedSource {
    val isDoublePage = ImageUtil.isWideImage(imageSource)
    return if (isDoublePage) {
        val rotation = if (dualPageRotateToFitInvert) -90f else 90f
        onTransform(
            if (rotation > 0) PageImageTransform.ROTATE_CLOCKWISE else PageImageTransform.ROTATE_COUNTERCLOCKWISE,
        )
        ImageUtil.rotateImage(imageSource, rotation)
    } else {
        imageSource
    }
}

private fun DualPageSettings.splitInHalf(
    imageSource: BufferedSource,
    page: ReaderPage,
    isLeftToRight: Boolean,
    onTransform: (PageImageTransform) -> Unit,
): BufferedSource {
    var side = when {
        isLeftToRight && page is InsertPage -> ImageUtil.Side.RIGHT
        !isLeftToRight && page is InsertPage -> ImageUtil.Side.LEFT
        isLeftToRight && page !is InsertPage -> ImageUtil.Side.LEFT
        else -> ImageUtil.Side.RIGHT
    }

    if (dualPageInvert) {
        side = when (side) {
            ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
            ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
        }
    }

    onTransform(if (side == ImageUtil.Side.LEFT) PageImageTransform.LEFT_HALF else PageImageTransform.RIGHT_HALF)
    return ImageUtil.splitInHalf(imageSource, side)
}
