// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
val localProperties = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}
android {
    namespace = "chromahub.rhythm.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "chromahub.rhythm.app"
        minSdk = 26
        targetSdk = 37
        val overrideVersionCode = project.findProperty("versionCodeOverride")?.toString()?.toIntOrNull()
        val overrideVersionName = project.findProperty("versionNameOverride")?.toString()
        versionCode = overrideVersionCode ?: 554991332
        versionName = overrideVersionName ?: "5.5.499.1332"

        val overrideReleaseDate = project.findProperty("releaseDateOverride")?.toString()
        buildConfigField("String", "RELEASE_DATE", "\"${overrideReleaseDate ?: "2026-10-26"}\"")

        val isNightly = project.findProperty("nightly")?.toString() == "true"
        buildConfigField("boolean", "IS_NIGHTLY", isNightly.toString())

        // Apple Music credentials are supplied by the build environment or local configuration only.
        val appleMusicToken = System.getenv("APPLE_MUSIC_FALLBACK_TOKEN")
            ?: localProperties.getProperty("APPLE_MUSIC_FALLBACK_TOKEN")
            ?: ""
        buildConfigField("String", "APPLE_MUSIC_FALLBACK_TOKEN", "\"$appleMusicToken\"")

        listOf("ENABLE_YOUTUBE_MUSIC", "ENABLE_LYRICALLY_API", "ENABLE_DEEZER", "ENABLE_LRCLIB",
            "ENABLE_BETTERLYRICS", "ENABLE_SPOTIFY_SEARCH", "ENABLE_WIKIPEDIA").forEach {
            buildConfigField("boolean", it, "true")
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("github") { dimension = "distribution"; versionNameSuffix = "-gh" }
        create("fdroid") { dimension = "distribution"; versionNameSuffix = "-fdroid" }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            // Shared classes must retain their published ABI; independent shrinking is gated later.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    sourceSets {
        getByName("main") {
            res.directories.add(rootProject.file("app/src/main/res").path)
        }
        getByName("debug").res.directories.add(rootProject.file("app/src/debug/res").path)
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin.compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi", "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions.unitTests.isReturnDefaultValues = true
    // Retain the original application's lint policy after moving its unchanged music sources.
    lint {
        abortOnError = true
        disable.addAll(listOf("MissingTranslation", "UnsafeOptInUsageError", "NonObservableLocale",
            "StateFlowValueCalledInComposition", "LocalContextGetResourceValueCall", "UnusedMaterial3ScaffoldPaddingParameter"))
    }
    packaging.resources {
        merges += "/META-INF/INDEX.LIST"
        merges += "**/io.netty.versions.properties"
    }
}
dependencies {
    implementation(project(":runtime-components"))
    implementation(project(":crawler"))
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5") {
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation(libs.net.jthink.jaudiotagger)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.com.squareup.okhttp3.mockwebserver3)
    testImplementation(libs.org.json)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
composeCompiler { includeComposeMappingFile.set(false) }


/** D8 sees only feature-owned classes as program input. The fixed SDK remains in the base. */
@CacheableTask
abstract class DexRuntimeFeature : JavaExec() {
    @get:InputFiles @get:Classpath abstract val programJars: ListProperty<RegularFile>
    @get:InputFiles @get:Classpath abstract val programDirectories: ListProperty<Directory>
    @get:InputFiles @get:Classpath abstract val runtimeJars: ListProperty<RegularFile>
    @get:InputFiles @get:Classpath abstract val runtimeDirectories: ListProperty<Directory>
    @get:Input abstract val pinnedVersions: MapProperty<String, String>
    @get:Input abstract val resolvedVersions: MapProperty<String, String>
    @get:InputFiles @get:Classpath abstract val privateLibraries: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidLibrary: RegularFileProperty
    @get:InputFiles @get:Classpath abstract val desugarLibraries: ConfigurableFileCollection
    @get:OutputFile abstract val dexArchive: RegularFileProperty
    @TaskAction override fun exec() {
        val pinned = pinnedVersions.get()
        resolvedVersions.get().forEach { (id, version) ->
            val expected = pinned[id]
            check(expected == null || expected == version) {
                "Shared SDK version changed: $id ($expected -> $version); establish a new base compatibility contract."
            }
        }
        val ownClasses = temporaryDir.resolve("feature-classes.jar")
        ownClasses.parentFile.mkdirs()
        val seen = mutableSetOf<String>()
        ZipOutputStream(ownClasses.outputStream()).use { output ->
            fun add(name: String, bytes: ByteArray) {
                if (!name.endsWith(".class")) return
                if (!name.startsWith("chromahub/rhythm/app/") && !name.startsWith("chromahub/rhythm/module/")) {
                    check(Regex(".*/R(?:\\$[^/]+)?\\.class").matches(name)) { "Unexpected shared class in feature inputs: $name" }
                    return // Library R symbols belong to the fixed shared SDK.
                }
                check(seen.add(name)) { "Duplicate feature class: $name" }
                output.putNextEntry(ZipEntry(name).apply { time = 0 })
                output.write(bytes); output.closeEntry()
            }
            programJars.get().forEach { jar -> ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    if (entry.name.endsWith(".class")) add(entry.name, zip.getInputStream(entry).use { it.readBytes() })
                }
            } }
            programDirectories.get().forEach { directory ->
                directory.asFile.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
                    add(file.relativeTo(directory.asFile).invariantSeparatorsPath, file.readBytes())
                }
            }
        }
        check("chromahub/rhythm/module/RhythmFeatureModule.class" in seen)
        check("chromahub/rhythm/app/activities/MainActivity.class" !in seen)
        check("chromahub/rhythm/runtime/api/RuntimeFeatureModule.class" !in seen)
        val output = dexArchive.get().asFile.also { it.parentFile.mkdirs() }
        val arguments = mutableListOf("--min-api", "26", "--lib", androidLibrary.get().asFile.path, "--output", output.path)
        val desugarConfiguration = temporaryDir.resolve("desugar.json")
        val configuration = desugarLibraries.files.firstNotNullOf { jar ->
            ZipFile(jar).use { zip -> zip.getEntry("META-INF/desugar/d8/desugar.json")?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes() }
            } }
        }
        desugarConfiguration.writeBytes(configuration)
        arguments.addAll(listOf("--desugared-lib", desugarConfiguration.path))
        runtimeJars.get().forEach { arguments.addAll(listOf("--classpath", it.asFile.path)) }
        runtimeDirectories.get().forEach { arguments.addAll(listOf("--classpath", it.asFile.path)) }
        arguments.add(ownClasses.path)
        arguments.addAll(privateLibraries.files.map { it.path })
        setArgs(arguments)
        super.exec()
    }
}

abstract class ExportSharedRuntimeContract : DefaultTask() {
    @get:InputFiles @get:Classpath abstract val artifacts: ListProperty<RegularFile>
    @get:Input abstract val coordinates: ListProperty<String>
    @get:OutputFile abstract val contractFile: RegularFileProperty
    @TaskAction fun export() {
        val entries = coordinates.get().zip(artifacts.get()).map { (id, artifact) ->
            val hash = MessageDigest.getInstance("SHA-256")
            artifact.asFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            mapOf("id" to id, "sha256" to hash.digest().joinToString("") { "%02x".format(it) })
        }
        contractFile.get().asFile.apply { parentFile.mkdirs(); writeText(groovy.json.JsonOutput.toJson(entries) + "\n") }
    }
}

/** Test compile input only; the test APK must not link a second copy of feature implementation. */
@CacheableTask
abstract class ExportRuntimeFeatureClasses : DefaultTask() {
    @get:InputFiles @get:Classpath abstract val jars: ListProperty<RegularFile>
    @get:InputFiles @get:Classpath abstract val directories: ListProperty<Directory>
    @get:OutputFile abstract val classesJar: RegularFileProperty
    @TaskAction fun export() {
        val output = classesJar.get().asFile.also { it.parentFile.mkdirs() }
        val seen = mutableSetOf<String>()
        ZipOutputStream(output.outputStream()).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                if (!(name.startsWith("chromahub/rhythm/app/") || name.startsWith("chromahub/rhythm/module/") ||
                    name.startsWith("META-INF/") && name.endsWith(".kotlin_module"))) return
                check(seen.add(name))
                zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
            }
            jars.get().forEach { file -> ZipFile(file.asFile).use { input ->
                input.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    add(entry.name, input.getInputStream(entry).use { it.readBytes() })
                }
            } }
            directories.get().forEach { directory -> directory.asFile.walkTopDown().filter { it.isFile }.forEach {
                add(it.relativeTo(directory.asFile).invariantSeparatorsPath, it.readBytes())
            } }
        }
    }
}

// Resolve immutable dependency identities during configuration, then give tasks only file inputs.
androidComponents.onVariants { variant ->
    val runtime = configurations.getByName("${variant.name}RuntimeClasspath")
    val shared = configurations.detachedConfiguration(dependencies.project(mapOf("path" to ":runtime-components")))
    runtime.attributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST") val typedKey = key as Attribute<Any>
        shared.attributes.attribute(typedKey, requireNotNull(runtime.attributes.getAttribute(typedKey)))
    }
    shared.attributes.attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm)
    val sharedComponents = shared.incoming.resolutionResult.allComponents.map { it.id }.toSet()
    val pinned = sharedComponents.filterIsInstance<ModuleComponentIdentifier>().associate { "${it.group}:${it.module}" to it.version }
    val runtimeVersions = runtime.incoming.resolutionResult.rootComponent.map { root ->
        val visited = mutableSetOf<ComponentIdentifier>()
        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component.id)) return
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { visit(it.selected) }
        }
        visit(root)
        visited.filterIsInstance<ModuleComponentIdentifier>().associate { "${it.group}:${it.module}" to it.version }
    }
    val privateArtifacts = runtime.incoming.artifactView {
        componentFilter { it !in sharedComponents }
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    }
    val sharedClasses = shared.incoming.artifactView {
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
    }
    tasks.register<ExportSharedRuntimeContract>("export${variant.name.replaceFirstChar(Char::uppercase)}SharedContract") {
        dependsOn(sharedClasses.files)
        val ordered = sharedClasses.artifacts.artifacts.sortedBy { it.id.componentIdentifier.displayName }
        artifacts.set(ordered.map { layout.projectDirectory.file(it.file.absolutePath) })
        coordinates.set(ordered.map { it.id.componentIdentifier.displayName })
        contractFile.set(layout.buildDirectory.file("outputs/runtime-feature/${variant.name}/shared-contract.json"))
    }
    val dex = tasks.register<DexRuntimeFeature>("dex${variant.name.replaceFirstChar(Char::uppercase)}Feature") {
        pinnedVersions.set(pinned)
        resolvedVersions.set(runtimeVersions)
        privateLibraries.from(privateArtifacts.files)
        desugarLibraries.from(configurations.getByName("coreLibraryDesugaring"))
        // Project artifacts must have their producing tasks completed before D8 reads them.
        dependsOn(":crawler:jar")
        androidLibrary.set(androidComponents.sdkComponents.bootClasspath.map { it.first() })
        dexArchive.set(layout.buildDirectory.file("outputs/runtime-feature/${variant.name}/content.dex.jar"))
        classpath = files(com.android.tools.r8.D8::class.java.protectionDomain.codeSource.location)
        mainClass.set("com.android.tools.r8.D8")
        maxHeapSize = "3g"
    }
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(dex).toGet(
        ScopedArtifact.CLASSES, DexRuntimeFeature::programJars, DexRuntimeFeature::programDirectories)
    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(dex).toGet(
        ScopedArtifact.CLASSES, DexRuntimeFeature::runtimeJars, DexRuntimeFeature::runtimeDirectories)
    val testClasses = tasks.register<ExportRuntimeFeatureClasses>("export${variant.name.replaceFirstChar(Char::uppercase)}FeatureClasses") {
        classesJar.set(layout.buildDirectory.file("outputs/runtime-feature/${variant.name}/feature-classes.jar"))
    }
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(testClasses).toGet(
        ScopedArtifact.CLASSES, ExportRuntimeFeatureClasses::jars, ExportRuntimeFeatureClasses::directories)
    val testCompile = configurations.create("runtime${variant.name.replaceFirstChar(Char::uppercase)}FeatureClasses") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    artifacts.add(testCompile.name, testClasses.flatMap { it.classesJar })
}

// Keep the native base and feature resource contracts identical.
@CacheableTask
abstract class PrepareRuntimeStableIds : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val contract: RegularFileProperty
    @get:Input abstract val packageName: Property<String>
    @get:OutputFile abstract val idsFile: RegularFileProperty
    @TaskAction fun prepare() {
        idsFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(contract.get().asFile.readText().replace("chromahub.rhythm.app:", "${packageName.get()}:"))
        }
    }
}
androidComponents.onVariants { variant ->
    val prepare = tasks.register<PrepareRuntimeStableIds>("prepare${variant.name.replaceFirstChar(Char::uppercase)}RuntimeStableIds") {
        contract.set(rootProject.layout.projectDirectory.file("runtime-contract/resource-ids-v1.txt"))
        packageName.set(variant.applicationId)
        idsFile.set(layout.buildDirectory.file("generated/runtime-resource-ids/${variant.name}.txt"))
    }
    variant.androidResources.aaptAdditionalParameters.addAll(prepare.flatMap { it.idsFile }.map {
        listOf("--stable-ids", it.asFile.path)
    })
}
