plugins { id("com.android.application") }

android {
    namespace = "chromahub.rhythm.app"
    compileSdk = 37
    defaultConfig {
        // Same ID as the installed test host: archive resources must not resolve to its old APK.
        applicationId = "chromahub.rhythm.app.debug"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "resource-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { compileOnly(project(":runtime-api")) }
