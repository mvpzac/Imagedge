// :lut LUT 引擎（Vulkan 计算着色器 + 纯 Kotlin CPU 回退，参考 Lut2Photo 架构）
// M0 骨架：先声明 Android library；Vulkan NDK 在 M3 落地
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.imagedge.camera.lut"
    compileSdk = 37

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
}

dependencies {
    api(project(":core"))
    api(project(":raw"))

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
