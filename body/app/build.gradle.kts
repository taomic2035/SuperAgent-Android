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

    // DS-011：sherpa 用 AAsset_getBuffer(mmap) 读**所有**资产（不只模型）——
    // lexicon.txt/tokens.txt/fst/utf8 被 DEFLATE → 规则 FST 加载静默失败 → 生成空音频。
    // 全量 noCompress：onnx/bin/txt/fst/utf8/dict（dict 是 jieba 词典扩展名）。
    androidResources {
        noCompress += listOf("onnx", "bin", "txt", "fst", "utf8", "dict")
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
}