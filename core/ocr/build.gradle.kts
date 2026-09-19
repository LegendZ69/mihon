import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    // Preserve the resource namespace used by the official OpenCV Java classes.
    namespace = "org.opencv"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

// OpenCV 5's published libc++_shared.so is only 4 KB aligned. Extract the official
// Java/native distribution and replace precisely that runtime with the pinned NDK's.
// Local JARs and generated jniLibs are correctly packaged in Android library AARs.
val opencvDistribution by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

val prepareOpenCv by tasks.registering(PrepareOpenCv::class) {
    distribution.set(layout.file(providers.provider { opencvDistribution.singleFile }))
    runtimes.from(
        androidComponents.sdkComponents.ndkDirectory.map { ndk ->
            val host = ndk.dir("toolchains/llvm/prebuilt").asFile.listFiles().orEmpty().single { it.isDirectory }
            listOf(
                "aarch64-linux-android",
                "arm-linux-androideabi",
                "i686-linux-android",
                "x86_64-linux-android",
            ).map { triple ->
                host.resolve("sysroot/usr/lib/$triple/libc++_shared.so")
            }
        },
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/opencv"))
    jniDirectory.set(outputDirectory.dir("jni"))
    resDirectory.set(outputDirectory.dir("res"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareOpenCv, PrepareOpenCv::jniDirectory)
    variant.sources.res?.addGeneratedSourceDirectory(prepareOpenCv, PrepareOpenCv::resDirectory)
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    opencvDistribution("org.opencv:opencv:5.0.0@aar")
    implementation(files(prepareOpenCv.flatMap { it.outputDirectory.file("classes.jar") }))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp.core)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
}

@CacheableTask
abstract class PrepareOpenCv : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val distribution: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimes: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val jniDirectory: DirectoryProperty

    @get:Internal
    abstract val resDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val source = distribution.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(checksum == "02906576c8ed6a728916853fb2f6df020af6ce6eacab527ae89ded8d973cbf6d") {
            "Official OpenCV 5.0.0 artifact checksum mismatch"
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        ZipFile(source).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory || entry.name.endsWith("/libc++_shared.so")) continue
                if (entry.name != "classes.jar" && !entry.name.startsWith("jni/") &&
                    !entry.name.startsWith("res/")
                ) {
                    continue
                }
                val target = output.resolve(entry.name)
                check(target.canonicalPath.startsWith(output.canonicalPath + "/"))
                target.parentFile.mkdirs()
                archive.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
            }
        }
        val abiByTriple = mapOf(
            "aarch64-linux-android" to "arm64-v8a",
            "arm-linux-androideabi" to "armeabi-v7a",
            "i686-linux-android" to "x86",
            "x86_64-linux-android" to "x86_64",
        )
        for (runtime in runtimes) {
            val abi = abiByTriple.getValue(runtime.parentFile.name)
            runtime.copyTo(output.resolve("jni/$abi/libc++_shared.so"), overwrite = true)
        }
    }
}
