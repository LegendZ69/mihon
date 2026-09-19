package mihon.gradle.tasks

import com.android.tools.r8.StringConsumer
import com.android.tools.r8.tracereferences.TraceReferences
import com.android.tools.r8.tracereferences.TraceReferencesCommand
import com.android.tools.r8.tracereferences.TraceReferencesKeepRules
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Preserve only app APIs referenced by the separately compiled instrumentation APK. */
abstract class GenerateInstrumentationKeepRulesTask : DefaultTask() {
    @get:Classpath
    abstract val appJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val appDirectories: ListProperty<Directory>

    @get:Classpath
    abstract val testJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val testDirectories: ListProperty<Directory>

    @get:Classpath
    abstract val bootClasspath: ListProperty<RegularFile>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val targets = temporaryDir.resolve("app-classes.jar")
        val targetClasses = archive(appJars.get().map { it.asFile }, appDirectories.get().map { it.asFile }, targets)
        val sources = temporaryDir.resolve("test-classes.jar")
        // Shared dependencies are loaded from the target APK on Android. Trace calls to those
        // original classes, rather than treating a second test copy as an independent definition.
        archive(testJars.get().map { it.asFile }, testDirectories.get().map { it.asFile }, sources, targetClasses)
        val output = outputFile.get().asFile.apply { parentFile.mkdirs() }
        TraceReferences.run(
            TraceReferencesCommand.builder()
                .addLibraryFiles(bootClasspath.get().map { it.asFile.toPath() })
                .addTargetFiles(targets.toPath())
                .addSourceFiles(sources.toPath())
                .setConsumer(
                    TraceReferencesKeepRules.builder()
                        .setOutputConsumer(StringConsumer.FileConsumer(output.toPath()))
                        .build(),
                )
                .build(),
        )
        check(output.isFile && output.length() > 0) { "No instrumentation keep rules were generated" }
    }

    private fun archive(
        jars: List<File>,
        directories: List<File>,
        output: File,
        excluded: Set<String> = emptySet(),
    ): Set<String> {
        val included = mutableSetOf<String>()
        ZipOutputStream(output.outputStream().buffered()).use { destination ->
            fun add(name: String, input: () -> java.io.InputStream) {
                if (!name.endsWith(".class") || name == "module-info.class" ||
                    name in excluded || !included.add(name)
                ) {
                    return
                }
                destination.putNextEntry(ZipEntry(name).apply { time = 0L })
                input().use { it.copyTo(destination) }
                destination.closeEntry()
            }
            directories.filter { it.isDirectory }.sortedBy { it.path }.forEach { directory ->
                directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    add(file.relativeTo(directory).invariantSeparatorsPath, file::inputStream)
                }
            }
            jars.filter { it.isFile }.sortedBy { it.path }.forEach { jar ->
                ZipFile(jar).use { source ->
                    source.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.forEach { entry ->
                        add(entry.name) { source.getInputStream(entry) }
                    }
                }
            }
        }
        return included
    }
}
