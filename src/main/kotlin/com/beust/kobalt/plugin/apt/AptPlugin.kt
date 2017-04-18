package com.beust.kobalt.plugin.apt

import com.beust.kobalt.Constants
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.*
import com.beust.kobalt.api.annotation.AnnotationDefault
import com.beust.kobalt.api.annotation.Directive
import com.beust.kobalt.homeDir
import com.beust.kobalt.internal.CompilerUtils
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.maven.aether.Filters
import com.beust.kobalt.maven.aether.Scope
import com.beust.kobalt.maven.dependency.FileDependency
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltLogger
import com.beust.kobalt.misc.warn
import com.beust.kobalt.plugin.kotlin.KotlinPlugin
import com.google.common.collect.ArrayListMultimap
import com.google.inject.Inject
import java.io.File
import java.util.*
import javax.inject.Singleton

/**
 * The AptPlugin manages both apt and kapt. Each of them has two components:
 * 1) A new apt directive inside a dependency{} block (similar to compile()) that declares where
 * the annotation processor is found
 * 2) An apt{} configuration on Project that lets the user configure how the annotation is performed
 * (outputDir, etc...).
 */
@Singleton
class AptPlugin @Inject constructor(val dependencyManager: DependencyManager, val kotlinPlugin: KotlinPlugin,
        val compilerUtils: CompilerUtils)
    : BasePlugin(), ICompilerFlagContributor, ISourceDirectoryContributor, IClasspathContributor, ITaskContributor {

    companion object {
        const val PLUGIN_NAME = "Apt"
        const val KAPT_CONFIG = "kaptConfig"
        const val APT_CONFIG = "aptConfig"
    }

    override val name = PLUGIN_NAME

    var kaptConfig: KaptConfig? = null

    override fun apply(project: Project, context: KobaltContext) {
        super.apply(project, context)
        kaptConfig = kaptConfigs[project.name]

        // Delete the output directories
        listOf(aptConfigs[project.name]?.outputDir, kaptConfig?.outputDir)
            .filterNotNull()
            .distinct()
            .map { generatedDir(project, it) }
            .forEach {
                it.normalize().absolutePath.let { path ->
                    context.logger.log(project.name, 1, "  Deleting " + path)
                    val success = it.deleteRecursively()
                    if (!success) warn("  Couldn't delete " + path)
                }
            }
    }

    // IClasspathContributor
    override fun classpathEntriesFor(project: Project?, context: KobaltContext): Collection<IClasspathDependency> {
        val result = arrayListOf<IClasspathDependency>()
        if (project != null && kaptConfig != null) {
            kaptConfig?.let { config ->
                val c = generatedClasses(project, context, config.outputDir)
                File(c).mkdirs()
                result.add(FileDependency(c))
            }
        }
        return result
    }

    private fun generatedDir(project: Project, outputDir: String) : File
            = File(KFiles.joinDir(project.directory, KFiles.KOBALT_BUILD_DIR, outputDir))

    // ISourceDirectoryContributor
    override fun sourceDirectoriesFor(project: Project, context: KobaltContext): List<File> {
        val result = arrayListOf<File>()
        aptConfigs[project.name]?.let { config ->
            result.add(generatedDir(project, config.outputDir))
        }

        kaptConfigs[project.name]?.let { config ->
            result.add(generatedDir(project, config.outputDir))
        }

        return result
    }

    private fun generated(project: Project, context: KobaltContext, outputDir: String) =
            KFiles.joinAndMakeDir(project.directory, project.buildDirectory, outputDir)

    private fun generatedSources(project: Project, context: KobaltContext, outputDir: String) =
            KFiles.joinDir(generated(project, context, outputDir), "sources")
    private fun generatedStubs(project: Project, context: KobaltContext, outputDir: String) =
            KFiles.joinDir(generated(project, context, outputDir), "stubs")
    private fun generatedClasses(project: Project, context: KobaltContext, outputDir: String) =
            KFiles.joinDir(generated(project, context, outputDir), "classes")

    // ITaskContributor
    override fun tasksFor(project: Project, context: KobaltContext): List<DynamicTask> {
        val result =
            if (kaptConfig != null) {
                listOf(
                    DynamicTask(this, "runKapt", "Run kapt", AnnotationDefault.GROUP, project,
                        reverseDependsOn = listOf("compile"), runAfter = listOf("clean"),
                            closure = {p: Project -> taskRunKapt(p)}),
                    DynamicTask(this, "compileKapt", "Compile the sources generated by kapt",
                        AnnotationDefault.GROUP, project,
                        dependsOn = listOf("runKapt"), reverseDependsOn = listOf("compile"),
                            closure = {p: Project -> taskCompileKapt(p)})
                )
            } else {
                emptyList()
            }
        return result
    }

    fun taskCompileKapt(project: Project) : TaskResult {
        var success = true
        kaptConfigs[project.name]?.let { config ->
            val sourceDirs = listOf(
                    generatedStubs(project, context, config.outputDir),
                    generatedSources(project, context, config.outputDir))
            val sourceFiles = KFiles.findSourceFiles(project.directory, sourceDirs, listOf("kt")).toList()
            val buildDirectory = File(KFiles.joinDir(project.directory,
                    generatedClasses(project, context, config.outputDir)))
            val flags = listOf<String>()
            val cai = CompilerActionInfo(project.directory, allDependencies(), sourceFiles, listOf(".kt"),
                    buildDirectory, flags, emptyList(), forceRecompile = true)

            val cr = compilerUtils.invokeCompiler(project, context, kotlinPlugin.compiler, cai)
            success = cr.failedResult == null
        }

        return TaskResult(success)
    }

    val annotationDependencyId = "org.jetbrains.kotlin:kotlin-annotation-processing:" +
            Constants.KOTLIN_COMPILER_VERSION

    fun annotationProcessorDependency() = dependencyManager.create(annotationDependencyId)

    fun aptJarDependencies() : List<IClasspathDependency> {
        val apDep = dependencyManager.create("net.thauvin.erik.:semver:0.9.6-beta")
        val apDep2 = FileDependency(homeDir("t/semver-example-kotlin/lib/semver-0.9.7.jar"))
        return listOf(apDep2)
    }

    fun allDependencies(): List<IClasspathDependency> {
        val allDeps = listOf(annotationProcessorDependency()) + aptJarDependencies()

        return allDeps
    }

    fun taskRunKapt(project: Project) : TaskResult {
        var success = true
        val flags = arrayListOf<String>()
        kaptConfig?.let { config ->


            val generated = generated(project, context, config.outputDir)
            val generatedSources = generatedSources(project, context, config.outputDir).replace("//", "/")
            File(generatedSources).mkdirs()

            //
            // Tell the Kotlin compiler to use the annotation plug-in
            //
            val allDeps = allDependencies()
            flags.add("-Xplugin")
            flags.add(annotationProcessorDependency().jarFile.get().absolutePath)
            flags.add("-P")

            //
            // Pass options to the annotation plugin
            //
            fun kaptPluginFlag(flagValue: String) = "plugin:org.jetbrains.kotlin.kapt3:$flagValue"
            val kaptPluginFlags = arrayListOf<String>()
            val verbose = KobaltLogger.LOG_LEVEL >= 2
            listOf("sources=" + generatedSources,
                    "classes=" + generatedClasses(project, context, config.outputDir),
                    "stubs=" + generatedStubs(project, context, config.outputDir),
                    "verbose=$verbose",
                    "aptOnly=true").forEach {
                kaptPluginFlags.add(kaptPluginFlag(it))
            }

            //
            // Dependencies for the annotation plug-in and the generation
            //
            val dependencies = dependencyManager.calculateDependencies(project, context,
                    Filters.EXCLUDE_OPTIONAL_FILTER,
                    listOf(Scope.COMPILE),
                    allDeps)
            dependencies.forEach {
                val jarFile = it.jarFile.get()
                kaptPluginFlags.add(kaptPluginFlag("apclasspath=$jarFile"))
            }

            flags.add(kaptPluginFlags.joinToString(","))
            listOf("-language-version", "1.1", " -api-version", "1.1").forEach {
                flags.add(it)
            }

            val sourceFiles =
                KFiles.findSourceFiles(project.directory, project.sourceDirectories, listOf("kt"))
                        .toList() + generatedSources
            val buildDirectory = File(KFiles.joinDir(project.directory, generated))
            val cai = CompilerActionInfo(project.directory, allDeps, sourceFiles, listOf(".kt"),
                    buildDirectory, flags, emptyList(), forceRecompile = true)

            context.logger.log(project.name, 2, "  " + kaptPluginFlags.joinToString("\n  "))
            val cr = compilerUtils.invokeCompiler(project, context, kotlinPlugin.compiler, cai)
            success = cr.failedResult == null
        }

        return TaskResult(success)
    }

    // ICompilerFlagContributor
    override fun compilerFlagsFor(project: Project, context: KobaltContext, currentFlags: List<String>,
            suffixesBeingCompiled: List<String>): List<String> {
        val result = arrayListOf<String>()

        // Only run for Java files
        if (!suffixesBeingCompiled.contains("java")) return emptyList()

        fun addFlags(outputDir: String) {
            aptDependencies[project.name]?.let {
                result.add("-s")
                result.add(generatedSources(project, context, outputDir))
            }
        }

        aptConfigs[project.name]?.let { config ->
            addFlags(config.outputDir)
        }

        context.logger.log(project.name, 2, "New flags from apt: " + result.joinToString(" "))
        return result
    }

    private val aptDependencies = ArrayListMultimap.create<String, String>()

    fun addAptDependency(dependencies: Dependencies, it: String) {
        aptDependencies.put(dependencies.project.name, it)
    }

    private val aptConfigs: HashMap<String, AptConfig> = hashMapOf()
    private val kaptConfigs: HashMap<String, KaptConfig> = hashMapOf()

    fun addAptConfig(project: Project, kapt: AptConfig) {
        project.projectProperties.put(APT_CONFIG, kapt)
        aptConfigs.put(project.name, kapt)
    }

    fun addKaptConfig(project: Project, kapt: KaptConfig) {
        project.projectProperties.put(KAPT_CONFIG, kapt)
        kaptConfigs.put(project.name, kapt)
    }
}

class AptConfig(var outputDir: String = "generated/source/apt")

@Directive
fun Project.apt(init: AptConfig.() -> Unit) {
    AptConfig().let {
        it.init()
        (Kobalt.findPlugin(AptPlugin.PLUGIN_NAME) as AptPlugin).addAptConfig(this, it)
    }
}

@Directive
fun Dependencies.apt(vararg dep: String) {
    dep.forEach {
        (Kobalt.findPlugin(AptPlugin.PLUGIN_NAME) as AptPlugin).addAptDependency(this, it)
    }
}

class KaptConfig(var outputDir: String = "generated/source/apt")

@Directive
fun Project.kapt(init: KaptConfig.() -> Unit) {
    KaptConfig().let {
        it.init()
        (Kobalt.findPlugin(AptPlugin.PLUGIN_NAME) as AptPlugin).addKaptConfig(this, it)
    }
}
