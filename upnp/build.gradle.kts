// :upnp UPnP/SOAP 协议栈（"发送到智能手机"模式，端口 64321）
// 参考 EdgeFlow imagingedge 的协议流程，自研实现（OkHttp + JDK DOM 解析，零 xmlutil 依赖）
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
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
