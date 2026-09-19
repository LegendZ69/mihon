import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.FileOutputStream

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.compose)
}

val upstreamViewer by configurations.creating {
    isTransitive = false
}
val generatedUpstream = layout.buildDirectory.dir("generated/upstream")

val prepareUpstreamViewer by tasks.registering {
    inputs.files(upstreamViewer)
    outputs.dir(generatedUpstream)
    doLast {
        val output = generatedUpstream.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        upstreamViewer.files.forEach { artifact ->
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
                        name.startsWith("jni/") || name.startsWith("assets/") ||
                            name.startsWith("res/") -> {
                            val mergedName = if (name.startsWith("res/values/")) {
                                "res/values/${artifact.nameWithoutExtension.replace('-', '_').replace('.', '_')}_${name.substringAfterLast('/')}"
                            } else name
                            val file = output.resolve(mergedName)
                            file.parentFile.mkdirs()
                            archive.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) }
                        }
                        name.endsWith("LICENSE.txt") || name == "proguard.txt" -> {
                            val file = output.resolve(if (name == "proguard.txt") name else "resources/$name")
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
    upstreamViewer("ca.mpreg:webgpuviewer:47@aar")
    upstreamViewer("com.github.mihonapp:subsampling-scale-image-view:94915e6f73@aar")
    implementation(libs.image.decoder)
    api(files(generatedUpstream.map { it.file("webgpu-api.jar") }).builtBy(prepareUpstreamViewer))
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.bundles.kotlinx.coroutines)
}
