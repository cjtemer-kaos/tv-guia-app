plugins {
    id("com.android.application")
}

android {
    namespace = "com.kaos.tvguia"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kaos.tvguia"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // la web vive en app/src/main/assets/web
    sourceSets["main"].assets.srcDirs("src/main/assets")
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}



