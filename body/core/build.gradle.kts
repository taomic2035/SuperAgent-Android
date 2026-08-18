plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.superagent.body.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        ndk {
            // 只带真机所需 ABI（sherpa-onnx 仅 arm64-v8a 资产）
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        getByName("main") {
            // third/ 下的开源资产：不拷贝进源码树，直接引用
            jniLibs.srcDirs("../../third/sherpa-onnx/jniLibs")
            java.srcDirs("../../third/sherpa-onnx/kotlin-api/src/main/kotlin")
        }
    }

    androidResources {
        // onnx/wav 模型须保持未压缩，sherpa 用 mmap/AAsset_getBuffer 读取
        noCompress += listOf("onnx", "wav")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nanohttpd)
    // 汉字转拼音：ASR 专名纠错（同 Kestrel）
    implementation(libs.pinyin4j)
    // HITL 通知（NotificationCompat）
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}