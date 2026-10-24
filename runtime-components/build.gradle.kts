plugins { id("com.android.library") }

android {
    namespace = "chromahub.rhythm.runtime.components"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    constraints {
        // These empty compatibility artifacts must resolve identically in the base and feature.
        api("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24")
        api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
    }
    api(project(":runtime-api"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    api("com.journeyapps:zxing-android-embedded:4.3.0")
    api("com.google.zxing:core:3.5.3")
    api("com.alphacephei:vosk-android:0.3.75")
    api("androidx.webkit:webkit:1.12.1")
    api(libs.androidx.core.ktx)
    api(libs.androidx.core.splashscreen)
    api(libs.androidx.core)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.activity.compose)
    api(libs.androidx.appcompat)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    api(libs.androidx.ui.tooling.preview)
    api(libs.androidx.material3.android)
    api(libs.androidx.material3.window.size)
    api(libs.androidx.window)
    api(libs.com.google.android.material)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.exoplayer.dash)
    api(libs.androidx.media3.exoplayer.hls)
    api(libs.androidx.media3.ui)
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.datasource.okhttp)
    api(libs.androidx.media3.exoplayer.midi)
    api(libs.org.jellyfin.media3.ffmpeg.decoder)
    api(libs.androidx.palette.ktx)
    api(libs.androidx.glance.appwidget)
    api(libs.androidx.glance.material3)
    api(libs.androidx.compose.animation)
    api(libs.androidx.compose.animation.graphics)
    api(libs.androidx.compose.animation.core)
    api(libs.reorderables)
    api(libs.androidx.navigation.compose)
    api(libs.com.google.accompanist.accompanist.permissions)
    api(libs.androidx.fragment.ktx)
    api(libs.androidx.mediarouter)
    api(libs.io.coil.kt.coil.compose)
    api(libs.taglib)
    api(libs.com.squareup.retrofit2.retrofit)
    api(libs.com.squareup.retrofit2.converter.gson)
    api(libs.com.squareup.okhttp3.okhttp)
    api(libs.com.squareup.okhttp3.logging.interceptor)
    api(libs.com.google.code.gson.gson)
    api(libs.org.jetbrains.kotlinx.coroutines.core)
    api(libs.org.jetbrains.kotlinx.coroutines.android)
    api(libs.androidx.foundation.layout)
    api(libs.androidx.work.runtime.ktx)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.androidx.room.paging)
    api(libs.androidx.paging.runtime.ktx)
    api(libs.androidx.paging.compose)
    api(libs.androidx.profileinstaller)
    debugApi(libs.androidx.ui.tooling)
    debugApi(libs.androidx.ui.test.manifest)
    debugApi(libs.com.squareup.leakcanary.leakcanary.android)
}
