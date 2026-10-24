plugins { id("com.android.library") }

android {
    namespace = "chromahub.rhythm.runtime.host"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
            allWarningsAsErrors.set(true)
        }
    }
}

dependencies {
    api(project(":runtime-api"))
    api(project(":runtime-core"))
}
