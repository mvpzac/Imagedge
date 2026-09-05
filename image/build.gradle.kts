// :image 基础图像处理（调整 + 几何变换 + 非破坏性编辑栈）
//
// 定位：一站式「编辑」环节的公共底座。
// - **非破坏性**：只记录 EditStep 列表，渲染时才应用到像素；
//   原图永不被改写，调整可随时回退、复用、批量套用
// - **纯库**：不引 Hilt、不依赖具体页面，便于测试与被其它模块复用
// - 性能：颜色类步骤合成单个 ColorMatrix，一次 Canvas 绘制完成，
//   避免逐像素运算（GPU 友好，大图也不卡）
//
// alpha 说明：本模块为新增，现有 edit/ 下的 LUT、EXIF 边框、三格图、
// 视频转 Live Photo **不受影响**，后续再逐步统一到这条管线。

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.imagedge.camera.image"
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
