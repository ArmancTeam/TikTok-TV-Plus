plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.armanc.team"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.armanc.team"
        minSdk = 21
        targetSdk = 34
        versionCode = (project.findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 2
        versionName = (project.findProperty("VERSION_NAME") as String?) ?: "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // No suffix: keep debug/release under the same applicationId
            // so WebView cookie/session storage stays consistent between
            // builds during development.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.10.0")
}
