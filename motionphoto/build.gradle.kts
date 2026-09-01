// :motionphoto Motion Photo（LIVE 图）合成/解析库
// 移植自 SuoxingTech/MotionPhotoLab（MIT License），核心为 Media3 MuxerUtil + 多厂商 XMP 对齐
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imagedge.camera.motionphoto"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        // 存量错误入基线（2026-09-01 生成，56 项，Media3 UnstableApi 等移植代码），之后只对新增问题报警
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(libs.androidx.exifinterface)
    implementation(libs.media3.common)
    implementation(libs.media3.muxer)
    // LIVE 图选段裁剪（Transformer）与分辨率钳制（Presentation，media3-effect）
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
