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

    // 자바 코드 컴파일 설정
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Temi SDK
    implementation("com.robotemi:sdk:1.136.0")

    // AndroidX 기본 UI
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX for camera functionality
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")


    implementation("androidx.webkit:webkit:1.8.0")

    // stt 모델 vosk
    implementation(group = "com.alphacephei", name = "vosk-android", version = "0.3.32+")
}