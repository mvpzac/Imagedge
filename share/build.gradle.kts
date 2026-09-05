// :share 导出与分享（一站式闭环的最后一环）
//
// 职责：把「已传输到本机的照片」按用户配置导出为可分享的副本
// （尺寸档位 / 格式 / 质量 / EXIF 隐私策略），并交给系统分享面板。
//
// 设计原则（alpha）：
// - 纯库模块，不引入 Hilt；Context 由调用方注入，便于测试与复用
// - 只产出**缓存副本**，绝不改写原图——导出失败或配置变更不影响已下载的照片
// - 不申请任何新权限：写缓存目录、通过 FileProvider 授权临时读权限

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.imagedge.camera.share"
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
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
}
