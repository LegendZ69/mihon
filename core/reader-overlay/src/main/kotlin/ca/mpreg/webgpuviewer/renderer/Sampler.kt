package ca.mpreg.webgpuviewer.renderer

/**
 * The WGSL [TileRenderer] resolves a high-quality tile with in one step, through [RenderPage.render].
 *
 * [code] defines `resolve(src_start, scale) -> vec4<f32>`: the top-left of one destination pixel's
 * footprint in window texels, and its size in texels (under 1 per axis enlarges). Written against
 * [RenderPage]'s header: `transform`, `src_tex0..3`, `contentSize`, `totalLoad`, `to_linear_exact`.
 * Reads stay within `contentSize()`: past it is padding.
 *
 * Any sampler handles any ratio, so either of [TileRenderer]'s two roles may take it. Networks that
 * change resolution are [ImageFilter]s instead.
 */
class Sampler(val code: String) {
    companion object {
        private val CATMULL_ROM by lazy { readWgslResource("catmull_rom.wgsl") }
        private val BOX by lazy { readWgslResource("box_downscale.wgsl") }

        /** Catmull-Rom, kernel stretched where a pixel covers more than a texel. */
        fun catmullRom() = Sampler(CATMULL_ROM)

        /** Area-weighted average of the source pixels each destination pixel covers. */
        fun box() = Sampler(BOX)
    }
}
