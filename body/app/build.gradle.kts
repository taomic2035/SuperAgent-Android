plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.superagent.body"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.superagent.body"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = false
    }

    // sherpa-onnx 靠 AAsset_getBuffer(mmap) 读模型，仅对 STORED 资产有效。
    // 压缩存储会退化成整块解压进内存：325MB Kokoro 载入瞬时 RSS 1GB+，
    // 华为 iaware/SWAP 直接 SIGKILL（P1 TTS 门根因，2026-08-19）。
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
}