/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.plugin

import org.gradle.api.*
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Usage
import org.gradle.api.capabilities.Capability
import org.gradle.api.component.ComponentWithVariants
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.FeaturePreviews
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.component.UsageContext
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.language.cpp.CppBinary
import org.gradle.language.cpp.internal.DefaultUsageContext
import org.gradle.language.cpp.internal.NativeVariantIdentity
import org.gradle.nativeplatform.Linkage
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.KonanPlugin.Companion.COMPILE_ALL_TASK_NAME
import org.jetbrains.kotlin.gradle.plugin.model.KonanToolingModelBuilder
import org.jetbrains.kotlin.gradle.plugin.tasks.*
import org.jetbrains.kotlin.konan.KonanVersion
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.customerDistribution
import org.jetbrains.kotlin.konan.util.DependencyProcessor
import java.io.File
import javax.inject.Inject

/**
 * We use the following properties:
 *      konan.home          - directory where compiler is located (aka dist in konan project output).
 *      konan.version       - a konan compiler version for downloading.
 *      konan.build.targets - list of targets to build (by default all the declared targets are built).
 *      konan.jvmArgs       - additional args to be passed to a JVM executing the compiler/cinterop tool.
 */

internal fun Project.hasProperty(property: KonanPlugin.ProjectProperty) = hasProperty(property.propertyName)
internal fun Project.findProperty(property: KonanPlugin.ProjectProperty): Any? = findProperty(property.propertyName)

internal fun Project.getProperty(property: KonanPlugin.ProjectProperty) = findProperty(property)
        ?: throw IllegalArgumentException("No such property in the project: ${property.propertyName}")

internal fun Project.getProperty(property: KonanPlugin.ProjectProperty, defaultValue: Any) =
        findProperty(property) ?: defaultValue

internal fun Project.setProperty(property: KonanPlugin.ProjectProperty, value: Any) {
    extensions.extraProperties.set(property.propertyName, value)
}

// konanHome extension is set by downloadKonanCompiler task.
internal val Project.konanHome: String
    get() {
        assert(hasProperty(KonanPlugin.ProjectProperty.KONAN_HOME))
        return project.file(getProperty(KonanPlugin.ProjectProperty.KONAN_HOME)).canonicalPath
    }

internal val Project.konanBuildRoot          get() = buildDir.resolve("konan")
internal val Project.konanBinBaseDir         get() = konanBuildRoot.resolve("bin")
internal val Project.konanLibsBaseDir        get() = konanBuildRoot.resolve("libs")
internal val Project.konanBitcodeBaseDir     get() = konanBuildRoot.resolve("bitcode")

internal fun File.targetSubdir(target: KonanTarget) = resolve(target.visibleName)

internal val Project.konanDefaultSrcFiles         get() = fileTree("${projectDir.canonicalPath}/src/main/kotlin")
internal fun Project.konanDefaultDefFile(libName: String)
        = file("${projectDir.canonicalPath}/src/main/c_interop/$libName.def")

@Suppress("UNCHECKED_CAST")
internal val Project.konanArtifactsContainer: NamedDomainObjectContainer<KonanBuildingConfig<*>>
    get() = extensions.getByName(KonanPlugin.ARTIFACTS_CONTAINER_NAME)
            as NamedDomainObjectContainer<KonanBuildingConfig<*>>

// TODO: The Kotlin/Native compiler is downloaded manually by a special task so the compilation tasks
// are configured without the compile distribution. After target management refactoring
// we need .properties files from the distribution to configure targets. This is worked around here
// by using HostManager instead of PlatformManager. But we need to download the compiler at the configuration
// stage (e.g. by getting it from maven as a plugin dependency) and bring back the PlatformManager here.
internal val Project.hostManager: HostManager
    get() = findProperty("hostManager") as HostManager? ?:
        HostManager(customerDistribution(konanHome))

internal val Project.konanTargets: List<KonanTarget>
    get() = hostManager.toKonanTargets(konanExtension.targets)
                .filter{ hostManager.isEnabled(it) }
                .distinct()

@Suppress("UNCHECKED_CAST")
internal val Project.konanExtension: KonanExtension
    get() = extensions.getByName(KonanPlugin.KONAN_EXTENSION_NAME) as KonanExtension

internal val Project.konanCompilerDownloadTask
    get() = tasks.getByName(KonanPlugin.KONAN_DOWNLOAD_TASK_NAME)

internal val Project.requestedTargets
    get() = findProperty(KonanPlugin.ProjectProperty.KONAN_BUILD_TARGETS)?.let {
        it.toString().trim().split("\\s+".toRegex())
    }.orEmpty()

internal val Project.jvmArgs
    get() = (findProperty(KonanPlugin.ProjectProperty.KONAN_JVM_ARGS) as String?)?.split("\\s+".toRegex()).orEmpty()

internal val Project.compileAllTask
    get() = getOrCreateTask(COMPILE_ALL_TASK_NAME)

internal fun Project.targetIsRequested(target: KonanTarget): Boolean {
    val targets = requestedTargets
    return (targets.isEmpty() || targets.contains(target.visibleName) || targets.contains("all"))
}

/** Looks for task with given name in the given project. Throws [UnknownTaskException] if there's not such task. */
private fun Project.getTask(name: String): Task = tasks.getByPath(name)

/**
 * Looks for task with given name in the given project.
 * If such task isn't found, will create it. Returns created/found task.
 */
private fun Project.getOrCreateTask(name: String): Task = with(tasks) {
    findByPath(name) ?: create(name, DefaultTask::class.java)
}

internal fun Project.konanCompilerName(): String =
        "kotlin-native-${project.simpleOsName}-${KonanVersion.CURRENT}"

internal fun Project.konanCompilerDownloadDir(): String =
        DependencyProcessor.localKonanDir.resolve(project.konanCompilerName()).absolutePath

// region Useful extensions and functions ---------------------------------------

internal fun MutableList<String>.addArg(parameter: String, value: String) {
    add(parameter)
    add(value)
}

internal fun MutableList<String>.addArgs(parameter: String, values: Iterable<String>) {
    values.forEach {
        addArg(parameter, it)
    }
}

internal fun MutableList<String>.addArgIfNotNull(parameter: String, value: String?) {
    if (value != null) {
        addArg(parameter, value)
    }
}

internal fun MutableList<String>.addKey(key: String, enabled: Boolean) {
    if (enabled) {
        add(key)
    }
}

internal fun MutableList<String>.addFileArgs(parameter: String, values: FileCollection) {
    values.files.forEach {
        addArg(parameter, it.canonicalPath)
    }
}

internal fun MutableList<String>.addFileArgs(parameter: String, values: Collection<FileCollection>) {
    values.forEach {
        addFileArgs(parameter, it)
    }
}

internal fun MutableList<String>.addListArg(parameter: String, values: List<String>) {
    if (values.isNotEmpty()) {
        addArg(parameter, values.joinToString(separator = " "))
    }
}

// endregion

internal fun dumpProperties(task: Task) {
    fun Iterable<String>.dump() = joinToString(prefix = "[", separator = ",\n${" ".repeat(22)}", postfix = "]")
    fun Collection<FileCollection>.dump() = flatMap { it.files }.map { it.canonicalPath }.dump()
    when (task) {
        is KonanCompileTask -> with(task) {
            println()
            println("Compilation task: $name")
            println("destinationDir     : $destinationDir")
            println("artifact           : ${artifact.canonicalPath}")
            println("srcFiles         : ${srcFiles.dump()}")
            println("produce            : $produce")
            println("libraries          : ${libraries.files.dump()}")
            println("                   : ${libraries.artifacts.map {
                it.artifact.canonicalPath
            }.dump()}")
            println("                   : ${libraries.namedKlibs.dump()}")
            println("nativeLibraries    : ${nativeLibraries.dump()}")
            println("linkerOpts         : $linkerOpts")
            println("enableDebug        : $enableDebug")
            println("noStdLib           : $noStdLib")
            println("noMain             : $noMain")
            println("enableOptimization : $enableOptimizations")
            println("enableAssertions   : $enableAssertions")
            println("noDefaultLibs      : $noDefaultLibs")
            println("target             : $target")
            println("languageVersion    : $languageVersion")
            println("apiVersion         : $apiVersion")
            println("konanVersion       : ${KonanVersion.CURRENT}")
            println("konanHome          : $konanHome")
            println()
        }
        is KonanInteropTask -> with(task) {
            println()
            println("Stub generation task: $name")
            println("destinationDir     : $destinationDir")
            println("artifact           : $artifact")
            println("libraries          : ${libraries.files.dump()}")
            println("                   : ${libraries.artifacts.map {
                it.artifact.canonicalPath
            }.dump()}")
            println("                   : ${libraries.namedKlibs.dump()}")
            println("defFile            : $defFile")
            println("target             : $target")
            println("packageName        : $packageName")
            println("compilerOpts       : $compilerOpts")
            println("linkerOpts         : $linkerOpts")
            println("headers            : ${headers.dump()}")
            println("linkFiles          : ${linkFiles.dump()}")
            println("konanVersion       : ${KonanVersion.CURRENT}")
            println("konanHome          : $konanHome")
            println()
        }
        else -> {
            println("Unsupported task.")
        }
    }
}

open class KonanExtension {
    var targets = mutableListOf("host")
    var languageVersion: String? = null
    var apiVersion: String? = null
    var jvmArgs = mutableListOf<String>()
}

open class KonanSoftwareComponent(val project: ProjectInternal?): SoftwareComponentInternal, ComponentWithVariants {
    private val usages = mutableSetOf<UsageContext>()
    override fun getUsages(): MutableSet<out UsageContext> = usages

    private val variants = mutableSetOf<SoftwareComponent>()
    override fun getName() = "main"

    override fun getVariants(): Set<SoftwareComponent> = variants

    fun addVariant(component: SoftwareComponent) = variants.add(component)
}

class KonanPlugin @Inject constructor(private val registry: ToolingModelBuilderRegistry)
    : Plugin<ProjectInternal> {

    enum class ProjectProperty(val propertyName: String) {
        KONAN_HOME                     ("konan.home"),
        KONAN_BUILD_TARGETS            ("konan.build.targets"),
        KONAN_JVM_ARGS                 ("konan.jvmArgs"),
        KONAN_USE_ENVIRONMENT_VARIABLES("konan.useEnvironmentVariables"),
        DOWNLOAD_COMPILER              ("download.compiler"),

        // Properties used instead of env vars until https://github.com/gradle/gradle/issues/3468 is fixed.
        // TODO: Remove them when an API for env vars is provided.
        KONAN_CONFIGURATION_BUILD_DIR  ("konan.configuration.build.dir"),
        KONAN_DEBUGGING_SYMBOLS        ("konan.debugging.symbols"),
        KONAN_OPTIMIZATIONS_ENABLE     ("konan.optimizations.enable"),
        KONAN_PUBLICATION_ENABLED      ("konan.publication.enabled")
    }

    companion object {
        internal const val ARTIFACTS_CONTAINER_NAME = "konanArtifacts"
        internal const val KONAN_DOWNLOAD_TASK_NAME = "checkKonanCompiler"
        internal const val KONAN_GENERATE_CMAKE_TASK_NAME = "generateCMake"
        internal const val COMPILE_ALL_TASK_NAME = "compileKonan"

        internal const val KONAN_EXTENSION_NAME = "konan"

        internal val REQUIRED_GRADLE_VERSION = GradleVersion.version("4.7")
    }

    private fun Project.cleanKonan() = project.tasks.withType(KonanBuildingTask::class.java).forEach {
        project.delete(it.artifact)
    }

    private fun checkGradleVersion() =  GradleVersion.current().let { current ->
        check(current >= REQUIRED_GRADLE_VERSION) {
            "Kotlin/Native Gradle plugin is incompatible with this version of Gradle.\n" +
            "The minimal required version is ${REQUIRED_GRADLE_VERSION}\n" +
            "Current version is ${current}"
        }
    }


    override fun apply(project: ProjectInternal?) {
        if (project == null) {
            return
        }
        checkGradleVersion()
        registry.register(KonanToolingModelBuilder)
        project.plugins.apply("base")
        // Create necessary tasks and extensions.
        project.tasks.create(KONAN_DOWNLOAD_TASK_NAME, KonanCompilerDownloadTask::class.java)
        project.tasks.create(KONAN_GENERATE_CMAKE_TASK_NAME, KonanGenerateCMakeTask::class.java)
        project.extensions.create(KONAN_EXTENSION_NAME, KonanExtension::class.java)
        val container = project.extensions.create(KonanArtifactContainer::class.java, ARTIFACTS_CONTAINER_NAME, KonanArtifactContainer::class.java, project)
        val isPublicationEnabled = project.gradle.services.get(FeaturePreviews::class.java).isFeatureEnabled(FeaturePreviews.Feature.GRADLE_METADATA)
        project.setProperty(ProjectProperty.KONAN_PUBLICATION_ENABLED, isPublicationEnabled)
        if (!isPublicationEnabled) {
            project.logger.warn("feature GRADLE_METADATA is not enabled: publication is disabled")
        }

        // Set additional project properties like konan.home, konan.build.targets etc.
        if (!project.hasProperty(ProjectProperty.KONAN_HOME)) {
            project.setProperty(ProjectProperty.KONAN_HOME, project.konanCompilerDownloadDir())
            project.setProperty(ProjectProperty.DOWNLOAD_COMPILER, true)
        }

        // Create and set up aggregate building tasks.
        val compileKonanTask = project.getOrCreateTask(COMPILE_ALL_TASK_NAME).apply {
            group = BasePlugin.BUILD_GROUP
            description = "Compiles all the Kotlin/Native artifacts"
        }
        project.getTask("build").apply {
            dependsOn(compileKonanTask)
        }
        project.getTask("clean").apply {
            doLast { project.cleanKonan() }
        }

        val runTask = project.getOrCreateTask("run")
        project.afterEvaluate {
            project.konanArtifactsContainer
                    .filterIsInstance(KonanProgram::class.java)
                    .forEach { program ->
                        program.forEach { compile ->
                            compile.runTask?.let { runTask.dependsOn(it) }
                        }
                    }
        }

        // Enable multiplatform support
        project.pluginManager.apply(KotlinNativePlatformPlugin::class.java)
        project.afterEvaluate {
            if (!isPublicationEnabled)
                return@afterEvaluate

            val requestedTargets = project.hostManager.targetValues.filter { val targetIsRequested = project.targetIsRequested(it)
                println("${it.name}: $targetIsRequested")
                targetIsRequested
            }
            project.logger.info("requested targets: ${requestedTargets.map { it.name }}")
            val targetsWeCantPublishFromHost = requestedTargets.filterNot { project.hostManager.isEnabled(it) }

            fun KonanTarget.asOperatingSystemFamily(): OperatingSystemFamily = project.objects.named(OperatingSystemFamily::class.java, family.name)
            project.logger.info("targets we can't process on current host: ${targetsWeCantPublishFromHost.map { it.name }}")
            project.pluginManager.withPlugin("maven-publish") {
                container.all { buildingConfig ->
                    val konanSoftwareComponent = buildingConfig.mainVariant
                    project.extensions.configure(PublishingExtension::class.java) {
                        val builtArtifact = buildingConfig.name
                        val mavenPublication = it.publications.maybeCreate(builtArtifact, MavenPublication::class.java)
                        mavenPublication.apply {
                            artifactId = builtArtifact
                            groupId = project.group.toString()
                            from(konanSoftwareComponent)
                        }
                        (mavenPublication as MavenPublicationInternal).publishWithOriginalFileName()
                        buildingConfig.pomActions.forEach {
                            mavenPublication.pom(it)
                        }
                    }

                    project.extensions.configure(PublishingExtension::class.java) {
                        val publishing = it
                        for (v in konanSoftwareComponent.variants) {
                            publishing.publications.create(v.name, MavenPublication::class.java) { mavenPublication ->
                                val coordinates = (v as NativeVariantIdentity).coordinates
                                project.logger.info("variant with coordinates($coordinates) and module: ${coordinates.module}")
                                mavenPublication.artifactId = coordinates.module.name
                                mavenPublication.groupId = coordinates.group
                                mavenPublication.version = coordinates.version
                                mavenPublication.from(v)
                                (mavenPublication as MavenPublicationInternal).publishWithOriginalFileName()
                                buildingConfig.pomActions.forEach {
                                    mavenPublication.pom(it)
                                }
                            }
                        }

                        val coordinates = (konanSoftwareComponent.variants.first() as NativeVariantIdentity).coordinates
                        for (target in targetsWeCantPublishFromHost) {
                            project.logger.info("processing... ${target.name}")
                            val objectFactory = project.objects
                            val linkUsage = objectFactory.named(Usage::class.java, Usage.NATIVE_LINK)
                            val artifactName = buildingConfig.name
                            val variantName = "${artifactName}_${target.name}"
                            val configuration = project.configurations.maybeCreate("artifact_fake_$artifactName")
                            val platformConfiguration = project.configurations.create("artifact_fake_${artifactName}_${target.name}")
                            platformConfiguration.extendsFrom(configuration)

                            platformConfiguration.attributes{
                                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.NATIVE_LINK))
                                it.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.STATIC)
                                it.attribute(CppBinary.OPTIMIZED_ATTRIBUTE, false)
                                it.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE, false)
                                it.attribute(Attribute.of("org.gradle.native.kotlin.platform", String::class.java), target.name)
                            }

                            val context = DefaultUsageContext(object:UsageContext {
                                override fun getUsage(): Usage = linkUsage
                                override fun getName(): String = "${variantName}Link"
                                override fun getCapabilities(): MutableSet<out Capability> = mutableSetOf()
                                override fun getDependencies(): MutableSet<out ModuleDependency> = mutableSetOf()
                                override fun getDependencyConstraints(): MutableSet<out DependencyConstraint> = mutableSetOf()
                                override fun getArtifacts(): MutableSet<out PublishArtifact> = mutableSetOf()
                                override fun getAttributes(): AttributeContainer = platformConfiguration.attributes
                            }, emptySet(), platformConfiguration)

                            val fakeVariant = NativeVariantIdentity(
                                    variantName,
                                    project.provider{ artifactName },
                                    project.provider{ project.group.toString() },
                                    project.provider{ project.version.toString() },
                                    false,
                                    false,
                                    target.asOperatingSystemFamily(),
                                    context,
                                    null)

                            publishing.publications.create(fakeVariant.name, MavenPublication::class.java) { mavenPublication ->
                                val coordinates = fakeVariant.coordinates
                                project.logger.info("fake variant with coordinates($coordinates) and module: ${coordinates.module}")
                                mavenPublication.artifactId = coordinates.module.name
                                mavenPublication.groupId = coordinates.group
                                mavenPublication.version = coordinates.version
                                mavenPublication.from(fakeVariant)
                                (mavenPublication as MavenPublicationInternal).publishWithOriginalFileName()
                                buildingConfig.pomActions.forEach {
                                    mavenPublication.pom(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
