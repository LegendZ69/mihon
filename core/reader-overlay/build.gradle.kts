import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.compose)
}

val upstreamViewer by configurations.creating {
    isTransitive = false
}
val generatedUpstream = layout.buildDirectory.dir("generated/upstream")

// Keeps the upstream AARs' WebGPU API classes, native libraries, resources, assets, licenses and
// consumer rules; the viewer classes themselves are rebuilt from the vendored sources.
val prepareUpstreamViewer by tasks.registering(PrepareUpstreamViewer::class) {
    artifacts.from(upstreamViewer)
    outputDirectory.set(generatedUpstream)
}

android {
    namespace = "ca.mpreg.webgpuviewer"
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("main").apply {
        jniLibs.srcDir(generatedUpstream.get().dir("jni").asFile)
        assets.srcDir(generatedUpstream.get().dir("assets").asFile)
        res.srcDir(generatedUpstream.get().dir("res").asFile)
        resources.srcDir(generatedUpstream.get().dir("resources").asFile)
    }
    defaultConfig.consumerProguardFiles(generatedUpstream.get().file("proguard.txt").asFile)
}

tasks.named("preBuild").configure { dependsOn(prepareUpstreamViewer) }

kotlin.compilerOptions.freeCompilerArgs.addAll(
    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
)

dependencies {
    upstreamViewer("ca.mpreg:webgpuviewer:${libs.versions.webgpuviewer.get()}@aar")
    upstreamViewer("com.github.mihonapp:subsampling-scale-image-view:${libs.versions.subsamplingScaleImageView.get()}@aar")
    implementation(libs.image.decoder)
    api(files(generatedUpstream.map { it.file("webgpu-api.jar") }).builtBy(prepareUpstreamViewer))
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.foundation)
    // Dependencies of the vendored v54 viewer classes, as its POM declares them.
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation("androidx.window:window:1.5.1")
    implementation(libs.bundles.kotlinx.coroutines)
}

@CacheableTask
abstract class PrepareUpstreamViewer : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        artifacts.files.forEach { artifact ->
            ZipFile(artifact).use { archive ->
                archive.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val name = entry.name
                    when {
                        name == "classes.jar" && artifact.name.startsWith("webgpuviewer-") -> {
                            ZipOutputStream(output.resolve("webgpu-api.jar").outputStream()).use { jar ->
                                ZipInputStream(archive.getInputStream(entry)).use { classes ->
                                    var item = classes.nextEntry
                                    while (item != null) {
                                        if (!item.isDirectory &&
                                            !item.name.startsWith("ca/mpreg/") &&
                                            item.name != "META-INF/library.kotlin_module"
                                        ) {
                                            jar.putNextEntry(ZipEntry(item.name))
                                            classes.copyTo(jar)
                                            jar.closeEntry()
                                        }
                                        item = classes.nextEntry
                                    }
                                }
                            }
                        }
                        name.startsWith("jni/") || name.startsWith("assets/") || name.startsWith("res/") -> {
                            val mergedName = if (name.startsWith("res/values/")) {
                                val prefix = artifact.nameWithoutExtension.replace('-', '_').replace('.', '_')
                                "res/values/${prefix}_${name.substringAfterLast('/')}"
                            } else {
                                name
                            }
                            val file = output.resolve(mergedName)
                            check(file.canonicalPath.startsWith(output.canonicalPath + "/"))
                            file.parentFile.mkdirs()
                            archive.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) }
                        }
                        name.endsWith("LICENSE.txt") || name == "proguard.txt" -> {
                            val file = output.resolve(if (name == "proguard.txt") name else "resources/$name")
                            check(file.canonicalPath.startsWith(output.canonicalPath + "/"))
                            file.parentFile.mkdirs()
                            archive.getInputStream(entry).use { input ->
                                FileOutputStream(file, name == "proguard.txt").use {
                                    input.copyTo(it)
                                    if (name == "proguard.txt") it.write('\n'.code)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
