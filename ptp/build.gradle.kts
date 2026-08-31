// :ptp PTP/IP 协议栈（ISO 15740，端口 15740）
// 纯 Kotlin 自研，参考 libptp 与 Sony-ZV-E10-RX 的实现结构，规避 LGPL 派生义务
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    api(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
