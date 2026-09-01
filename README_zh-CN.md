# Imagedge

[English](README.md) | [简体中文](README_zh-CN.md)

<div align="center">

![Version](https://img.shields.io/badge/version-0.1.9-1A1B1E?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-64--bit%20only-black?style=flat-square)

</div>

第三方开源的索尼相机无线传输与遥控 Android 应用，基于 **Kotlin + Jetpack Compose（Material 3）**。

> **已在索尼 ZV-E10（固件 2.03）上完整实测。** 具备相同无线功能的索尼机型大概率可用，欢迎反馈兼容性。

## 目录

- [功能](#功能)
- [架构](#架构)
- [环境要求](#环境要求)
- [构建](#构建)
- [项目状态与路线图](#项目状态与路线图)
- [参与贡献](#参与贡献)
- [许可与免责声明](#许可与免责声明)

## 功能

**传输（Wi-Fi）**
- **选片驱动相册**——相机端选片后手机自动显示（事件驱动即时刷新 + 4 秒轮询兜底）
- 缩略图浏览、全屏大图翻页查看、串行队列批量下载
- 断点续传分块下载（512 KiB 分块、指数退避、单块最多重试 5 次）
- **RAW（ARW）**——本地提取内嵌全尺寸 JPEG 预览，大图秒开
- 下载落盘系统相册（默认 `DCIM/Imagedge/`）或任意 SAF 自选目录，MediaStore 登记

**遥控拍摄**
- 实时取景（相机推流 18–30 fps、设备端限流约 20 fps；JPEG 帧提取方案，兼容固件帧格式变体）
- **蓝牙遥控快门**——系统配对 → 两段式快门（按下对焦、抬起拍摄）→ 录像切换，走相机「蓝牙遥控」GATT 协议
- **PTP `InitiateCapture` 兜底**（不支持蓝牙遥控的机型）
- PTP DeviceProp 远程参数控制：ISO / 光圈 / 快门 / 白平衡 / 曝光补偿 / 照相模式，与相机拨盘双向同步
- 遥控拍摄照片自动拉回（PTP 路径可用；BLE +「智能手机连接」路径受相机固件限制）

**连接**
- 扫码配网——扫相机屏幕的连接二维码（索尼 `W01:S:…;P:…;C:…;M:…` 格式，同时兼容标准 `WIFI:` 格式），解析 SSID/密码自动入网
- 自动网关发现 + 手动 IP 兜底
- 10 秒保活对抗相机 30 秒闲置踢线、事务超时 + socket 强关自愈、自动重连

**编辑**
- **LUT 调色**——内置胶片风格创意预设 + S-Log2/S-Log3 → Rec.709 还原 LUT，`.cube` 导入/导出/删除、强度可调
- CPU 三线性插值（纯 Kotlin）；Vulkan GPU 路径规划中
- **LIVE 图三拼**——将最多 3 张 LIVE 图/视频纵向拼接为一张 9:16 动态照片（每段可独立选封面/声音/顺序）
- **边框水印**——为任意照片叠加摄影参数信息边框（品牌 LOGO + 机型/焦距/光圈/快门/ISO…），EXIF 自动读取、可手动修正

**导出**
- **视频 → 动态照片（Motion Photo / LIVE Photo）**——选取一个或多个视频，逐段裁剪（≤ 5 秒）并选定封面帧（默认取段中间帧），封装为单文件动态照片，可被 Google / OPPO / 小米等相册直接识别播放（`:motionphoto` 模块，Media3 `MuxerUtil`）

**应用**
- 极简黑白 Material 3 主题——默认浅色，深色/跟随系统可选
- 传输记录（下载页长按查看）、权限管理页、触觉反馈、前台服务保活下载

## 架构

Gradle 多模块，按功能分包（PBF）：

| 模块 | 职责 |
|------|------|
| `:core` | 纯 Kotlin 基础库（流工具、日志），无 Android 依赖 |
| `:ptp` | PTP/IP 协议栈（ISO 15740），纯 Kotlin 实现，含索尼 SDIO 扩展 |
| `:upnp` | UPnP/SOAP 协议栈（相机「发送到智能手机」服务） |
| `:liveview` | LiveView 流（裸 60152 socket），纯 Kotlin——ZV-E10 无 Camera Web API 服务，故未用 JSON-RPC |
| `:raw` | RAW 解码：内嵌 JPEG 提取（TIFF 容器解析）；libraw NDK 规划中 |
| `:lut` | LUT 引擎：`.cube` 解析、CPU 三线性处理器（Vulkan 规划中） |
| `:motionphoto` | 视频 → 动态照片（Motion Photo / LIVE Photo）封装（Media3 `MuxerUtil`） |
| `:app` | Compose UI（MVVM + Hilt）、蓝牙快门、下载管理、设置 |

深入了解：

- [架构与数据流](docs/architecture.md)
- [索尼无线协议笔记](docs/sony-protocol-notes.md) —— 蓝牙快门码表、二维码格式、LiveView 帧、内容传输陷阱（真机实测沉淀，本项目最有价值的开源贡献之一）

## 环境要求

- Android 10+（minSdk 29），targetSdk 36
- **仅支持 64 位设备**（arm64-v8a / x86_64），不提供 32 位支持
- 具备 Wi-Fi「发送到智能手机 / 电脑遥控 / 蓝牙遥控」功能的索尼相机
- JDK 21、Android SDK（compileSdk 36）

## 构建

```bash
git clone https://github.com/mvpzac/Imagedge.git
cd Imagedge
./gradlew :app:assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

或直接用 **Android Studio**（Ladybug 及以上）打开工程点运行。

## 项目状态与路线图

- [x] P0/P1 核心链路（连接、相册、批量下载）真机验证
- [x] 蓝牙遥控快门、扫码配网、LUT 编辑
- [x] 视频 → 动态照片（Motion Photo / LIVE Photo）导出（`:motionphoto`）
- [x] 查看器视频预览
- [x] 遥控拍摄照片自动拉回（PTP 路径可用）
- [ ] 基于 libraw 的 RAW 真解码（NDK）
- [ ] Vulkan GPU LUT 处理器

## 参与贡献

欢迎 Issue 与 PR——见 [CONTRIBUTING.md](CONTRIBUTING.md)。特别欢迎对大家都有价值的协议知识（机型兼容性、实测怪癖）。

## 许可与免责声明

- 代码：[Apache-2.0](LICENSE)
- 内置 LUT 预设（`app/src/main/assets/luts/`）：8 个创意风格由 [EditClips](https://editclips.online) 生成（免费使用）+ S-Log2/S-Log3 → Rec.709 还原 LUT 使用 colour-science（索尼公开传输函数）自研生成——见各文件头注释
- **免责声明**：本项目与 Sony Corporation 无关亦未获其认可；Sony 及相关商标归其所有者所有。协议知识来自公开资料与社区逆向成果，仅供学习与互操作目的。请自行承担使用责任并遵守当地法律。
