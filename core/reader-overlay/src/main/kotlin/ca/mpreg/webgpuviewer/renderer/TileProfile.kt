package ca.mpreg.webgpuviewer.renderer

import android.util.Log
import java.util.Locale
import java.util.WeakHashMap

/** One tile run and its CPU cost, filled in as it goes. */
internal class TileRun(
    val page: String,
    val tx: Int,
    val ty: Int,
    val tileSize: Int,
    val scale: Float,
    val onScreen: Boolean,
    /** [TilesFilterChain] filters the tile went through. */
    val filters: List<String>,
    /** Side of the square drawn for them, halo included. */
    val inputSize: Int,
    /** Image filters, for a tile read from their cache; else empty. */
    val imageFilters: List<String>,
    /** Cache blocks rendered for this tile; 0 is a cache hit. */
    val rendered: Int,
    /** Left blank: the image filters cannot run on blocks. */
    val blank: Boolean,
    /** [System.nanoTime] at the start, before the chain is prepared. */
    val cpuStart: Long,
) {
    /** Preparing the chain, encoding every pass and submitting, in ms. */
    var cpuMs = 0.0
}

/**
 * Times each tile [TileRenderer] generates: a log line per tile, totals by kind in [summary].
 *
 * GPU time needs timestamp queries, which some adapters lack. A filtered tile splits into page draw,
 * each filter and final resize; the rest (gaps between passes, filters past [PROFILED_FILTERS]) is "other".
 */
internal class TileProfile {
    private class Totals(var count: Int = 0, var cpuMs: Double = 0.0, var gpuMs: Double = 0.0, var gpuCount: Int = 0)

    /** Off: no log, and no queries beyond those that pace batches. */
    @Volatile
    var enabled = false

    private val totals = LinkedHashMap<String, Totals>()
    private val pages = WeakHashMap<Any, Int>()
    private var pageCount = 0

    fun pageLabel(page: Any): String = "p${pages.getOrPut(page) { ++pageCount }}"

    /** [timestamps]: the tile's queries in ns, or null; the chain's are included if [run] had filters. */
    fun report(run: TileRun, timestamps: LongArray?) {
        val staged = run.filters.isNotEmpty()
        var gpuMs: Double? = null
        var detail = ""
        if (timestamps != null) {
            fun ms(from: Long, to: Long) = (to - from) / 1e6
            val gpu = ms(timestamps[0], timestamps[1])
            gpuMs = gpu
            // A tile that rendered cache blocks carries only its start and end.
            if (staged && timestamps.size > 2) {
                val parts = ArrayList<Pair<String, Double>>()
                parts.add("draw" to ms(timestamps[0], timestamps[2]))
                run.filters.take(PROFILED_FILTERS).forEachIndexed { i, name ->
                    val (begin, end) = filterQueries(i)
                    parts.add(name to ms(timestamps[begin], timestamps[end]))
                }
                parts.add("resize" to ms(timestamps[RESIZE_BEGIN_QUERY], timestamps[1]))
                val other = gpu - parts.sumOf { it.second }
                detail = " = " + parts.joinToString(" + ") { (name, t) -> "$name ${fmt(t)}" } + " + other ${fmt(other)}"
            }
        }

        val cached = run.imageFilters.isNotEmpty()
        val cache = if (run.blank) "blank" else if (run.rendered > 0) "cache render" else "cache hit"
        val kinds = (if (cached) listOf("${run.imageFilters.joinToString("+")} $cache") else emptyList()) +
                (if (staged) listOf(run.filters.joinToString("+")) else emptyList())
        val key = "${if (kinds.isNotEmpty()) kinds.joinToString(" | ") else "plain"} ${run.tileSize}px"
        synchronized(totals) {
            val t = totals.getOrPut(key) { Totals() }
            t.count++
            t.cpuMs += run.cpuMs
            if (gpuMs != null && gpuMs > 0) {
                t.gpuMs += gpuMs
                t.gpuCount++
            }
        }

        val parts = (if (cached) listOf(
            "image [${run.imageFilters.joinToString(", ")}] " +
                    if (run.rendered > 0) "cache render, ${run.rendered} blocks" else cache
        ) else emptyList()) +
                (if (staged) listOf("tiles [${run.filters.joinToString(", ")}] in ${run.inputSize}") else emptyList())
        Log.d(
            TAG,
            "${run.page} (${run.tx},${run.ty}) ${run.tileSize}px @${fmt(run.scale.toDouble())}x " +
                    "${if (run.onScreen) "on-screen" else "off-screen"} " +
                    "${if (parts.isNotEmpty()) parts.joinToString(" + ") else "plain"} | " +
                    "cpu ${fmt(run.cpuMs)}ms, gpu ${gpuMs?.let { "${fmt(it)}ms" } ?: "n/a"}$detail"
        )
    }

    /** Count and mean cost per kind of tile, one per line. */
    fun summary(): String = synchronized(totals) {
        totals.entries.joinToString("\n") { (key, t) ->
            val gpu = if (t.gpuCount > 0) "${fmt(t.gpuMs / t.gpuCount)}ms" else "n/a"
            "$key: ${t.count} tiles, cpu ${fmt(t.cpuMs / t.count)}ms, gpu $gpu"
        }
    }

    fun reset() = synchronized(totals) { totals.clear() }

    private companion object {
        const val TAG = "TileProfile"

        fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    }
}
