plugins { id("com.android.application") }

android {
    namespace = "chromahub.rhythm.module.resources"
    compileSdk = 37
    defaultConfig {
        applicationId = "chromahub.rhythm.module.resources"
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
