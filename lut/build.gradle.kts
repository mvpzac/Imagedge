// :lut LUT 引擎（GPU：OpenGL ES 3.0 + 3D 纹理硬件三线性 / CPU：纯 Kotlin 三线性兜底）
// 3D LUT 交给 sampler3D 的硬件过滤即可，无需 Vulkan NDK（见 GpuLutProcessor 类注释）
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
