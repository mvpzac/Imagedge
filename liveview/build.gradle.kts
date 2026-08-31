// :liveview Sony 裸 LiveView 流客户端（60152 端口 JPEG 扫描提取）
// 历史：本模块原为 :webapi（SSDP 发现 + JSON-RPC 拍照/参数），因 ZV-E10 无 Web API 服务，
// 2026-08-29 全量移除 Web API 代码，仅保留遥控页在用的 LiveView 裸流客户端。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    api(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
}
