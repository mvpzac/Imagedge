// :image 基础图像处理（调整 + 几何变换 + 非破坏性编辑栈）
//
// 定位：一站式「编辑」环节的公共底座。
// - **非破坏性**：只记录 EditStep 列表，渲染时才应用到像素；
//   原图永不被改写，调整可随时回退、复用、批量套用
// - **纯库**：不引 Hilt、不依赖具体页面，便于测试与被其它模块复用
// - 分工：本模块只做**几何栅格化**（`Bitmap.createBitmap` + `Matrix`：拉直/旋转/翻转/裁剪）
//   与**只读的曝光分析**（`ExposureAnalysis` 吃 IntArray，不写回像素）；调色与 LUT 的像素
//   一律在 :lut 完成，CPU 与 GPU 共用同一套线性光换算。
//   这里曾写过「颜色类步骤合成单个 ColorMatrix，一次 Canvas 绘制，避免逐像素运算」——那份
//   实现在 sRGB 编码空间里做乘性运算，而且没有任何调用方，批次 V（c3d2cea）已删。配方现在
//   确实带着 Color / Lut 两步，但解释它们的不是本模块，别照着旧说法在这里再写第二份。
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
    // EditStep.Color 直接携带 ColorAdjust：在 :image 里另抄一份七参数结构，
    // 就是本批次刚删掉的「两份调色定义必然漂移」的复现路径。
    api(project(":lut"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
