plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.teminai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.teminai"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Vosk 지원 ABI만 남기기 (Temi는 armeabi-v7a, arm64-v8a 둘 다 커버)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 🔥 여기는 일단 전부 지워두는 걸 추천
    // packagingOptions { ... } 도, sourceSets { jniLibs.srcDirs("libs") } 도 필요 없음
}

dependencies {
    implementation("com.robotemi:sdk:1.136.0")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    implementation("androidx.webkit:webkit:1.8.0")


    implementation("com.squareup.okhttp3:okhttp:4.11.0")
}