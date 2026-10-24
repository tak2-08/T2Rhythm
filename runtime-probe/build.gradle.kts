import java.util.zip.ZipFile

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "chromahub.rhythm.module.probe"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":runtime-api"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.ui)
    compileOnly(libs.androidx.material3.android)
    // Match the host's current shared stack. None of these libraries enter the probe AAR/DEX.
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.androidx.navigation.compose)
    compileOnly(libs.androidx.lifecycle.runtime.ktx)
    compileOnly("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycleRuntimeKtx.get()}")
    compileOnly(libs.org.jetbrains.kotlinx.coroutines.android)
    compileOnly("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
}

// AAR contains only probe classes; no Compose/Kotlin/shared API copies enter the DEX payload.
val probeAar = layout.buildDirectory.file("outputs/aar/runtime-probe-debug.aar")
val probeClasses = layout.buildDirectory.file("intermediates/runtime-probe/classes.jar")
val probeDex = layout.buildDirectory.file("outputs/runtime-probe/content.dex.jar")
// Use the same D8 as AGP, not an older SDK build-tools copy that cannot read Kotlin 2.4 metadata.
val dexCompiler = com.android.tools.r8.D8::class.java.protectionDomain.codeSource.location
val androidLibraries = androidComponents.sdkComponents.bootClasspath

tasks.register<JavaExec>("dexDebugProbe") {
    notCompatibleWithConfigurationCache("Experimental DEX fixture extraction")
    dependsOn("bundleDebugAar")
    inputs.file(probeAar)
    outputs.file(probeDex)
    classpath = files(dexCompiler)
    mainClass.set("com.android.tools.r8.D8")
    val bootLibraries = androidLibraries
    doFirst {
        val classes = probeClasses.get().asFile
        classes.parentFile.mkdirs()
        ZipFile(probeAar.get().asFile).use { zip ->
            zip.getInputStream(zip.getEntry("classes.jar")).use { input -> classes.outputStream().use { input.copyTo(it) } }
        }
        val output = probeDex.get().asFile
        output.parentFile.mkdirs()
        val androidLibrary = bootLibraries.get().first().asFile
        setArgs(listOf("--min-api", "26", "--lib", androidLibrary.path, "--output", output.path, classes.path))
    }
}
