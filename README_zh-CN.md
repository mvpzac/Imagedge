# Imagedge

[English](README.md) | [简体中文](README_zh-CN.md)

<div align="center">

![Version](https://img.shields.io/badge/version-0.2.0--alpha08-1A1B1E?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-64--bit%20only-black?style=flat-square)

</div>

第三方开源的索尼相机无线传输与遥控 Android 应用，基于 **Kotlin + Jetpack Compose（Material 3）**。

> **传输、遥控、配网在索尼 ZV-E10（固件 2.03）上实测通过。** 具备相同无线功能的索尼机型大概率可用，欢迎反馈兼容性。
> 编辑器最近的一批（撤销/重做、预设、裁剪框入配方）只在**模拟器 + debug 包**上实跑过，
> 真机 + release 那一遍尚未完成——逐条状态见 [CHANGELOG 的未验证清单](CHANGELOG.md)。

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
- 整文件流式下载（稳定性优先：`GetPartialObject` 分块下载在实测机型上被相机拒绝，分块实现保留在 `:ptp` 但默认关闭）
- **RAW（ARW）**——本地提取内嵌全尺寸 JPEG 预览，大图秒开
- 下载落盘系统相册（默认 `DCIM/Imagedge/`）或任意 SAF 自选目录；MediaStore 登记时写入 `IS_PENDING` 与拍摄时间，相册按拍摄时间排序、且不会看到写了一半的文件

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
- **编辑调节**（相册主编辑器）——编辑状态收敛为**一份配方**（`EditRecipe` = 有序的 `EditStep` 列表），
  撤销/重做是这份配方上的**游标**，不是另起一套命令栈。一页三分区：
  - **调色**：内置胶片风格创意预设 + S-Log2/S-Log3 → Rec.709 还原 LUT，`.cube` 导入/导出/删除；
    滤镜用你自己的照片渲染实时缩略图；滑条上方有**亮度直方图**（按最高桶归一化，削顶一眼看得出来）；
    强度 + 曝光 / 高光 / 阴影 / 色调 / 对比度 / 饱和度 / 色温七轴与滤镜在**同一遍像素处理**中完成
  - **裁剪**：比例预设（自由 / 1:1 / 4:3 / 3:2 / 16:9 / 9:16）+ 可拖动裁剪框（四角缩放、框内拖动整体移动）。
    框是配方的**最后一个几何步骤**，与旋转/翻转一起进历史——框若留在配方外，「撤销旋转 → 导出」
    得到的就是一张你当初没框住的内容
  - **旋转**：左右 90°、水平/垂直翻转（两个方向各自独立开关）、**拉直** −45°..45°（自动裁掉四角空白）
  - **撤销 / 重做**：滑条松手、点比例、翻转各自成一格；载入照片时会先播下「刚载入」那一格，
    所以第一次改动就退得回去
  - **预设**：调好的颜色与滤镜可存为预设、套用到别的照片（几何保留）、长按删除；覆盖同名会先问一句。
    落盘是一份配方的文本（`filesDir/edit_presets/<名字>.json`），未知键 / 未知步骤 / 越界一律**拒绝并给原因**
  - 预览可对比**调色前**的那一张（长按是快捷方式，另有一个显式按钮——长按不可发现，读屏用户也拿不到）
  - 导出按**原分辨率**重算（先几何后调色），格式 / 画质 / EXIF 策略三组与分享面板共用一套控件，保留 EXIF
- **GPU 加速**：LUT 走 OpenGL ES 3.0 的 3D 纹理（硬件三线性过滤、无需 NDK），EGL / 着色器 / 纹理上限不可用时自动回退到纯 Kotlin 的 CPU 实现
- **LIVE 图三拼**——最多 3 张实况图纵向拼接为一张动态照片（统一比例 + 逐段对齐/封面/声音/顺序），所见即所得预览 + 生成结果页
- **边框水印**——5 套模板（经典白边 / 暗色底栏 / 白框悬浮 / 双行签名 / 极简叠字），品牌 LOGO、机型、焦距、光圈、快门、ISO、拍摄时间自动读取，支持自定义文字（署名/地点/©）、逐字段开关与圆角，导出保留 EXIF

**导出**
- **视频 → 动态照片（Motion Photo / LIVE Photo）**——选取一个或多个视频，逐段裁剪（≤ 5 秒）并选定封面帧（默认取段中间帧），封装为单文件动态照片，可被 Google / OPPO / 小米等相册直接识别播放（`:motionphoto` 模块，Media3 `MuxerUtil`）
- **导出与分享**（`:share`）——尺寸档位（原图 / 2048px / 1080px / 2M）、格式（JPEG / PNG / WebP）、画质、EXIF 隐私策略（保留全部 / 仅清除 GPS / 清除全部）；导出只生成缓存副本、按 EXIF 转正后交给系统分享面板，不申请新权限、不集成第三方 SDK

**应用**
- 极简黑白 Material 3 主题——默认浅色，深色/跟随系统可选
- 传输记录（下载页长按查看）、权限管理页、触觉反馈、前台服务保活下载

## 架构

Gradle 多模块，按功能分包（PBF）：

| 模块 | 职责 |
|------|------|
| `:core` | 纯 Kotlin 基础库（流工具、日志），无 Android 依赖 |
| `:ptp` | PTP 协议栈（ISO 15740 + 索尼扩展），纯 Kotlin 实现；承载可换 TCP（PTP/IP）或 USB 批量端点，事务引擎只有一份 |
| `:upnp` | UPnP/SOAP 协议栈（相机「发送到智能手机」服务） |
| `:liveview` | LiveView 流（裸 60152 socket），纯 Kotlin——ZV-E10 无 Camera Web API 服务，故未用 JSON-RPC |
| `:raw` | RAW 解码：内嵌 JPEG 提取（TIFF 容器解析）；libraw NDK 规划中 |
| `:lut` | LUT 引擎：`.cube` 解析 + GPU 处理器（OpenGL ES 3.0 3D 纹理），CPU 三线性兜底；调色与键控数学的唯一一份 Kotlin 定义在这里，GLSL 是它的手写镜像 |
| `:motionphoto` | 视频 → 动态照片（Motion Photo / LIVE Photo）封装（Media3 `MuxerUtil`） |
| `:image` | 编辑配方与几何管线：有序的 `EditStep` 列表、游标历史（撤销/重做）、裁剪框等归一化坐标。**不做像素运算**——所有逐像素的活儿归 `:lut` |
| `:share` | 导出与分享：尺寸档位、格式、EXIF 隐私策略、系统分享面板 |
| `:app` | Compose UI（MVVM + Hilt）、蓝牙快门、下载管理、设置 |

深入了解：

- [架构与数据流](docs/architecture.md)

## 环境要求

- Android 10+（minSdk 29），targetSdk 37
- **仅支持 64 位设备**（arm64-v8a / x86_64），不提供 32 位支持
- 具备 Wi-Fi「发送到智能手机 / 电脑遥控 / 蓝牙遥控」功能的索尼相机
- JDK 21、Android SDK（compileSdk 37）

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
- [x] 导出与分享：尺寸档位 / 格式 / EXIF 隐私策略（`:share`）
- [x] 非破坏性编辑配方与几何变换（`:image`）
- [x] 撤销 / 重做（配方游标）与预设的存 / 套 / 删
- [x] 查看器视频预览
- [x] 遥控拍摄照片自动拉回（PTP 路径可用）
- [ ] 局部范围键控（亮度 / 色相 / 饱和度区间内只施加曝光·对比度·饱和度）——键控数学与
      预设编解码已就位，**界面与导出路径尚未接通**，CPU/GPU 同值只写了测试、没在设备上跑过
- [ ] 基于 libraw 的 RAW 真解码（NDK）
- [x] GPU LUT 处理器（OpenGL ES 3.0 3D 纹理 + CPU 回退）

## 参与贡献

欢迎 Issue 与 PR——见 [CONTRIBUTING.md](CONTRIBUTING.md)。特别欢迎对大家都有价值的协议知识（机型兼容性、实测怪癖）。

## 许可与免责声明

- 代码：[Apache-2.0](LICENSE)
- 第三方组件及其许可证：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 内置 LUT 预设（`app/src/main/assets/luts/`）：8 个创意风格由 [EditClips](https://editclips.online) 生成（免费使用）+ S-Log2/S-Log3 → Rec.709 还原 LUT 使用 colour-science（索尼公开传输函数）自研生成——见各文件头注释
- **免责声明**：本项目与 Sony Corporation 无关亦未获其认可；Sony 及相关商标归其所有者所有。协议知识来自公开资料与社区逆向成果，仅供学习与互操作目的。请自行承担使用责任并遵守当地法律。
