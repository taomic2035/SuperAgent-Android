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

    androidResources {
        // DS-011：sherpa 所有资产需 STORED（mmap），包括 lexicon/tokens/fst/dict/utf8
        noCompress += listOf("onnx", "bin", "txt", "fst", "utf8", "dict")
    }

    // 复盘教训 #2：跨层变更（XML+Kotlin）编译期类型安全——lint 检查 findViewById 类型匹配
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
}