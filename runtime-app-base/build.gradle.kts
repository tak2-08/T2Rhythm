// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}



val distributionPath = providers.gradleProperty("runtimeDistributionDir")
    .orElse(rootProject.layout.buildDirectory.dir("runtime-distribution").map { it.asFile.path })
val distributionDirectory = layout.dir(distributionPath.map { file(it) })
val hostMetadata = Properties().also { props ->
    distributionDirectory.get().asFile.resolve("host.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}
android {
    namespace = "chromahub.rhythm.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "chromahub.rhythm.app"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("versionCodeOverride").orElse("554991327").get().toInt()
        versionName = providers.gradleProperty("versionNameOverride").orElse("5.5.499.1327").get()
        manifestPlaceholders["runtimeTestOnly"] = providers.gradleProperty("runtimeTestOnly").orElse("false").get()
        buildConfigField("long", "RUNTIME_BASE_VERSION", "1L")
        buildConfigField("String", "RUNTIME_FINGERPRINT", "\"${hostMetadata.getProperty("runtimeFingerprint", "0".repeat(64))}\"")
        testInstrumentationRunner = "androidx.test.runner.NativeModuleJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-gh-debug" }
        release { isMinifyEnabled = false; versionNameSuffix = "-gh" }
    }
    sourceSets.getByName("main").res.directories.add(rootProject.file("app/src/main/res").path)
    sourceSets.getByName("debug").res.directories.add(rootProject.file("app/src/debug/res").path)
    sourceSets.getByName("androidTest").java.directories.add(rootProject.file("app/src/androidTest/java").path)
    sourceSets.getByName("androidTest").kotlin.directories.add(rootProject.file("app/src/androidTest/java").path)
    sourceSets.getByName("androidTest").assets.directories.add(rootProject.file("app/src/androidTest/assets").path)
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true; compose = true }
    kotlin.compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        "-Xfriend-paths=${rootProject.file("runtime-content/build/outputs/runtime-feature/debug/feature-classes.jar").path}"
    )
    packaging.resources {
        merges += "/META-INF/INDEX.LIST"
        merges += "**/io.netty.versions.properties"
    }
}

dependencies {
    implementation(project(":runtime-components"))
    implementation(project(":runtime-host"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestCompileOnly(project(mapOf("path" to ":runtime-content", "configuration" to "runtimeDebugFeatureClasses")))
    androidTestCompileOnly(project(":crawler"))
    androidTestCompileOnly("org.nanohttpd:nanohttpd:2.3.1")
}

@CacheableTask
abstract class PrepareEmbeddedFeature : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val distribution: DirectoryProperty
    @get:Input abstract val testOnly: Property<Boolean>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val productionPublicKey: RegularFileProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @TaskAction fun copy() {
        val input = distribution.get().asFile
        check(testOnly.get() || input.resolve("module-public.der").readBytes().contentEquals(productionPublicKey.get().asFile.readBytes())) {
            "A publishable base requires the pinned production signing key. Test trust requires runtimeTestOnly=true."
        }
        val output = assetsDirectory.get().asFile.resolve("runtime").also { it.mkdirs() }
        listOf("default.t2module", "module-public.der").forEach { name ->
            input.resolve(name).copyTo(output.resolve(name), overwrite = true)
        }
    }
}
val prepareEmbeddedFeature = tasks.register<PrepareEmbeddedFeature>("prepareEmbeddedFeature") {
    distribution.set(distributionDirectory)
    testOnly.set(providers.gradleProperty("runtimeTestOnly").map { it == "true" }.orElse(false))
    productionPublicKey.set(rootProject.layout.projectDirectory.file("runtime-contract/module-public.der"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/runtime-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareEmbeddedFeature, PrepareEmbeddedFeature::assetsDirectory)
}

abstract class PrepareNativeTestAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val dexJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resourcesApk: RegularFileProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @TaskAction fun copy() {
        val output = assetsDirectory.get().asFile.also { it.mkdirs() }
        dexJar.get().asFile.copyTo(output.resolve("content.dex.jar"), overwrite = true)
        resourcesApk.get().asFile.copyTo(output.resolve("resource-probe.apk"), overwrite = true)
    }
}
val prepareNativeTestAssets = tasks.register<PrepareNativeTestAssets>("prepareNativeTestAssets") {
    dependsOn(":runtime-probe:dexDebugProbe", ":runtime-resource-probe:assembleDebug")
    dexJar.set(rootProject.layout.projectDirectory.file("runtime-probe/build/outputs/runtime-probe/content.dex.jar"))
    resourcesApk.set(rootProject.layout.projectDirectory.file("runtime-resource-probe/build/outputs/apk/debug/runtime-resource-probe-debug.apk"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/runtime-test-assets"))
}
androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareNativeTestAssets, PrepareNativeTestAssets::assetsDirectory)
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
