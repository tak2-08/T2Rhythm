// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties
import java.io.File
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}



val moduleSigningPath = providers.gradleProperty("runtimeSigningKey")
    .orElse(providers.environmentVariable("RUNTIME_SIGNING_KEY_FILE"))
val runtimeTestOnly = providers.gradleProperty("runtimeTestOnly").map { it.toBooleanStrict() }
    .orElse(moduleSigningPath.map { false }.orElse(true))
val signingProperties = Properties().also { properties ->
    rootProject.file(".config/keystore.properties").takeIf { it.isFile }?.inputStream()?.use(properties::load)
}
android {
    namespace = "chromahub.rhythm.app"
    compileSdk = 37
    testBuildType = providers.gradleProperty("nativeTestBuildType").orElse("debug").get()
    defaultConfig {
        applicationId = "chromahub.rhythm.app"
        minSdk = 26
        targetSdk = 37
        val overrideVersionCode = providers.gradleProperty("versionCodeOverride").orNull?.toInt()
        val overrideVersionName = providers.gradleProperty("versionNameOverride").orNull
        versionCode = overrideVersionCode ?: 554991327
        versionName = overrideVersionName ?: "5.5.499.1327"
        manifestPlaceholders["runtimeTestOnly"] = runtimeTestOnly.get().toString()
        buildConfigField("long", "RUNTIME_BASE_VERSION", "1L")
        testInstrumentationRunner = "androidx.test.runner.NativeModuleJUnitRunner"
        // These methods intentionally span process exits; the lifecycle gate drives them in order.
        testInstrumentationRunnerArguments["notClass"] = "chromahub.rhythm.app.features.runtime.NativeUpdateLifecycleTest,chromahub.rhythm.app.features.runtime.NativeApkUpgradeTest"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("github") { dimension = "distribution"; versionNameSuffix = "-gh" }
        create("fdroid") { dimension = "distribution"; versionNameSuffix = "-fdroid" }
    }
    val apkSigning = if (signingProperties.isNotEmpty()) signingConfigs.create("release") {
        keyAlias = signingProperties.getProperty("key_alias")
        keyPassword = signingProperties.getProperty("key_password")
        storePassword = signingProperties.getProperty("store_password")
        storeFile = rootProject.file(signingProperties.getProperty("store_file"))
    } else signingConfigs.getByName("debug")
    check(!providers.gradleProperty("requireReleaseSigning").map { it == "true" }.getOrElse(false) || signingProperties.isNotEmpty()) {
        "The original APK signing configuration is required for publication."
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"; signingConfig = apkSigning }
        // The base publishes this complete SDK ABI to independently compiled future features.
        release { isMinifyEnabled = false; signingConfig = apkSigning }
        create("benchmark") { initWith(getByName("release")); matchingFallbacks += "release" }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true; compose = true }
    lint { disable.add("MissingTranslation") }
    kotlin.compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
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
    androidTestCompileOnly(project(":crawler"))
    androidTestCompileOnly("org.nanohttpd:nanohttpd:2.3.1")
}

/** Signing keys are execution inputs only and never enter Gradle's build cache. */
@UntrackedTask(because = "External private signing keys must never enter tracked or cached build inputs")
abstract class PrepareSignedRuntimeDistribution @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val featureApks: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val featureDex: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sharedContract: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val databaseImplementation: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aapt2: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packagingSources: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val notes: RegularFileProperty
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val testOnly: Property<Boolean>
    @get:Internal abstract val privateKey: RegularFileProperty
    @get:Internal abstract val repository: DirectoryProperty
    @get:OutputDirectory abstract val distribution: DirectoryProperty
    @TaskAction fun prepare() {
        val root = repository.get().asFile
        val key = privateKey.get().asFile
        if (testOnly.get() && !key.exists()) execOperations.exec {
            commandLine("python3", root.resolve("scripts/ensure_runtime_integration_key.py"), key)
        }
        check(key.isFile) { "Set runtimeSigningKey to the external PKCS#8 module signing key." }
        val apk = featureApks.get().asFile.listFiles()!!.single { it.extension == "apk" }
        val arguments = mutableListOf("python3", root.resolve("scripts/prepare_runtime_distribution.py").path,
            "--apk", apk.path, "--aapt2", aapt2.get().asFile.path, "--dex", featureDex.get().asFile.path,
            "--shared-contract", sharedContract.get().asFile.path, "--database-implementation", databaseImplementation.get().asFile.path,
            "--private-key", key.path, "--notes", notes.get().asFile.path, "--version-code", versionCode.get().toString(),
            "--version-name", versionName.get(), "--out", distribution.get().asFile.path)
        if (testOnly.get()) arguments.add("--test-only")
        execOperations.exec { commandLine(arguments) }
    }
}

@CacheableTask
abstract class PrepareEmbeddedFeature : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val distribution: DirectoryProperty
    @get:Input abstract val testOnly: Property<Boolean>
    @get:Input abstract val hasOriginalApkSigner: Property<Boolean>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val productionPublicKey: RegularFileProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @TaskAction fun copy() {
        check(testOnly.get() || hasOriginalApkSigner.get()) { "A publishable base APK requires the original Android signer." }
        val input = distribution.get().asFile
        check(testOnly.get() || input.resolve("module-public.der").readBytes().contentEquals(productionPublicKey.get().asFile.readBytes())) {
            "A publishable base requires the pinned production signing key. Test trust requires runtimeTestOnly=true."
        }
        val output = assetsDirectory.get().asFile.resolve("runtime").also { it.mkdirs() }
        listOf("default.t2module", "module-public.der", "host.properties").forEach { name ->
            input.resolve(name).copyTo(output.resolve(name), overwrite = true)
        }
    }
}
androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar(Char::uppercase)
    val featureVariant = if (variant.buildType == "benchmark") variant.name.replace("Benchmark", "Release") else variant.name
    val featureCapitalized = featureVariant.replaceFirstChar(Char::uppercase)
    val external = providers.gradleProperty("runtimeDistributionDir")
    val selectedDistribution = if (external.isPresent) layout.dir(external.map { File(it) }) else {
        val prepare = tasks.register<PrepareSignedRuntimeDistribution>("prepare${capitalized}RuntimeDistribution") {
            dependsOn(":runtime-content:assemble$featureCapitalized", ":runtime-content:dex${featureCapitalized}Feature", ":runtime-content:export${featureCapitalized}SharedContract")
            repository.set(rootProject.layout.projectDirectory)
            featureApks.set(rootProject.layout.projectDirectory.dir("runtime-content/build/outputs/apk/${variant.flavorName}/${if (variant.buildType == "benchmark") "release" else variant.buildType}"))
            featureDex.set(rootProject.layout.projectDirectory.file("runtime-content/build/outputs/runtime-feature/$featureVariant/content.dex.jar"))
            sharedContract.set(rootProject.layout.projectDirectory.file("runtime-content/build/outputs/runtime-feature/$featureVariant/shared-contract.json"))
            databaseImplementation.set(rootProject.layout.projectDirectory.file("runtime-content/build/generated/ksp/$featureVariant/kotlin/chromahub/rhythm/app/features/local/data/database/RhythmDatabase_Impl.kt"))
            aapt2.set(androidComponents.sdkComponents.sdkDirectory.map { it.file("build-tools/37.0.0/aapt2") })
            packagingSources.from(rootProject.fileTree("scripts") { include("prepare_runtime_distribution.py", "export_runtime_resource_ids.py", "ensure_runtime_integration_key.py") },
                rootProject.fileTree("runtime-contract"), rootProject.fileTree("runtime-api/src/main"), rootProject.fileTree("runtime-components/src/main"),
                rootProject.fileTree("runtime-host/src/main"), rootProject.fileTree("app/src/main/java"), rootProject.file("app/src/main/AndroidManifest.xml"))
            notes.set(rootProject.layout.projectDirectory.file("app/src/main/res/raw/current_release_notes.txt"))
            versionCode.set(variant.outputs.single().versionCode)
            versionName.set(variant.outputs.single().versionName)
            testOnly.set(runtimeTestOnly)
            privateKey.set(layout.file(moduleSigningPath.orElse(rootProject.layout.buildDirectory.file("runtime-signing/integration.pk8").map { it.asFile.path }).map { File(it) }))
            distribution.set(rootProject.layout.buildDirectory.dir("runtime-distribution/${variant.name}"))
        }
        prepare.flatMap { it.distribution }
    }
    val embedded = tasks.register<PrepareEmbeddedFeature>("prepare${capitalized}EmbeddedFeature") {
        distribution.set(selectedDistribution)
        testOnly.set(runtimeTestOnly)
        hasOriginalApkSigner.set(signingProperties.isNotEmpty())
        productionPublicKey.set(rootProject.layout.projectDirectory.file("runtime-contract/module-public.der"))
        assetsDirectory.set(layout.buildDirectory.dir("generated/runtime-assets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(embedded, PrepareEmbeddedFeature::assetsDirectory)
    variant.outputs.forEach { it.outputFileName.set("Rhythm-${android.defaultConfig.versionName}-${variant.name}.apk") }
    variant.androidTest?.let { test ->
        val featureCompile = configurations.create("native${capitalized}TestCompileOnly") {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        dependencies.add(featureCompile.name, dependencies.project(mapOf("path" to ":runtime-content", "configuration" to "runtime${featureCapitalized}FeatureClasses")))
        test.compileConfiguration.extendsFrom(featureCompile)
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            if (name == "compile${test.name.replaceFirstChar(Char::uppercase)}Kotlin") {
                compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${rootProject.file("runtime-content/build/outputs/runtime-feature/$featureVariant/feature-classes.jar").path}")
            }
        }
    }
}

abstract class PrepareNativeTestAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val dexJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resourcesApk: RegularFileProperty
    @get:Optional @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val lifecycleFixtures: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @TaskAction fun copy() {
        val output = assetsDirectory.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        dexJar.get().asFile.copyTo(output.resolve("content.dex.jar"), overwrite = true)
        resourcesApk.get().asFile.copyTo(output.resolve("resource-probe.apk"), overwrite = true)
        lifecycleFixtures.orNull?.asFile?.let { directory ->
            listOf("next.t2module", "bad-startup.t2module").forEach { name ->
                directory.resolve(name).copyTo(output.resolve(name), overwrite = true)
            }
        }
    }
}
val prepareNativeTestAssets = tasks.register<PrepareNativeTestAssets>("prepareNativeTestAssets") {
    dependsOn(":runtime-probe:dexDebugProbe", ":runtime-resource-probe:assembleDebug")
    dexJar.set(rootProject.layout.projectDirectory.file("runtime-probe/build/outputs/runtime-probe/content.dex.jar"))
    resourcesApk.set(rootProject.layout.projectDirectory.file("runtime-resource-probe/build/outputs/apk/debug/runtime-resource-probe-debug.apk"))
    providers.gradleProperty("runtimeLifecycleFixturesDir").orNull?.let { lifecycleFixtures.set(file(it)) }
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
