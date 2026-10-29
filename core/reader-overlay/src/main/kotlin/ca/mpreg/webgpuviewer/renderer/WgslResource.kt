package ca.mpreg.webgpuviewer.renderer

/** The WGSL in `src/main/resources/ca/mpreg/webgpuviewer/renderer/[name]`. */
internal fun readWgslResource(name: String): String {
    val stream = Sampler::class.java.getResourceAsStream(name)
        ?: error("Missing WGSL resource $name")
    return stream.use { it.bufferedReader().readText() }
}
