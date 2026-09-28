// :image 非破坏性编辑底座（配方 + 撤销游标 + 几何栅格化 + 纯几何数学 + 曝光/取景分析；调色与 LUT 像素在 :lut）
//
// 定位：一站式「编辑」环节的公共底座。
// - **非破坏性**：只记录 EditStep 列表，渲染时才应用到像素；
//   原图永不被改写，调整可随时回退、复用、批量套用
// - **纯库**：不引 Hilt、不依赖具体页面，便于测试与被其它模块复用
// - 分工：本模块做**几何**（两条不同的活：`ImagePipeline` 的栅格化——`Bitmap.createBitmap` +
//   `Matrix` 走拉直/旋转/翻转/裁剪；`Geometry`/`NormRect` 是纯数学换算，不碰像素，所以能在
//   JVM 上直接单测）、**编辑配方的数据形状**（`EditStep`/`EditRecipe`，只记录、不渲染像素）、
//   **只读的曝光分析**（`ExposureAnalysis` 吃 IntArray，不写回像素）与**取景帧的分析准入**
//   （`FrameGate`：≤5Hz、停用时零分析、不留队列）。调色与 LUT 的像素
//   一律在 :lut 完成，CPU 与 GPU 共用同一套线性光换算。
//   这里曾写过「颜色类步骤合成单个 ColorMatrix，一次 Canvas 绘制，避免逐像素运算」——那份
//   实现的毛病是算术直接做在 **gamma 编码值**上（既有乘法也有加法：亮度是三通道平移，
//   对比度绕编码 128 缩放），正是 :lut 刚修掉的同一个色彩空间错误；而且没有任何调用方，
//   批次 V（c3d2cea）已删。
//   配方现在确实带着 Color / Lut 两步，但解释它们的不是本模块，别照着旧说法在这里再写第二份。
//
// 现状：本模块**不闲置**——`PhotoEditViewModel` 今天就用 `ImagePipeline` 出预览与全分辨率导出
// 两条路径，几何换算走 `Geometry`/`NormRect`、直方图走 `ExposureAnalysis`；
// `CameraControlViewModel` 用 `FrameGate`/`ExposureAnalysis` 做取景分析。
// `feature/edit` 下其余入口（EXIF 边框、三格图、视频转 Live Photo）仍各自处理像素，
// 没并进这条管线。别把它写成「新增而无人使用」：那个说法在 `docs/HANDOFF.md` 里已被
// 更正（CHANGELOG 0.2.0-alpha04 的文档段），而它正是第二份 ColorMatrix 的来路——
// 「没人用」听起来就像「可以另写一份」。

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
