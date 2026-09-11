# 交接说明（HANDOFF）

> 面向接手本项目的开发者 / AI Agent。最后更新：2026-09-11，对应版本 **0.2.0-alpha03**。
> 需求与决策历史见 `.workbuddy/memory/`（按日期的工作日志 + `MEMORY.md` 长期备忘）。

## 这是什么

Sony 相机无线传输 / 遥控 Android 应用。Kotlin + Jetpack Compose (Material 3)，多模块 Gradle。
目标是一站式「连接 → 传输 → 编辑 → 分享」工作流。

- applicationId：`com.imagedge.camera`
- minSdk 29 / targetSdk 36 / compileSdk 37，**仅 64 位**（arm64-v8a、x86_64）
- 仓库：`mvpzac/Imagedge`（`main` 为稳定线，`alpha` 为当前开发线）

## 首次构建

1. 依赖：**JDK 21** + Android SDK（compileSdk 37）
2. 本机 SDK 路径（**未随包提供**，需自行创建）：
   ```properties
   # local.properties
   sdk.dir=/path/to/your/Android/sdk
   ```
3. 构建：
   ```bash
   ./gradlew :app:assembleDebug        # 调试包，任何人都能构建
   ./gradlew test                      # 单元测试
   ./gradlew :app:lintDebug            # 静态检查
   ```

### 关于 release 签名（重要）

`keystore.properties` 与 `*.jks` **已刻意排除**，不随本项目分发——它们是应用签名身份，泄漏等于应用被冒名。
没有它们时 `assembleRelease` 仍可构建（输出未签名 APK）。若要发布，需自行生成 keystore，
`app/build.gradle.kts` 会在检测到 `keystore.properties` 时自动启用签名。

## 模块结构

| 模块 | 职责 |
|---|---|
| `app` | UI + ViewModel + 导航（feature 按功能分包） |
| `core` | 跨模块通用类型 |
| `ptp` | PTP/IP 协议（相机控制与图片传输） |
| `upnp` | UPnP 发现（备用传输通道） |
| `liveview` | 实时取景 |
| `raw` | RAW / 内嵌 JPEG 解码 |
| `lut` | .cube LUT 解析与 CPU 应用 |
| `motionphoto` | 动态照片（移植自 SuoxingTech/MotionPhotoLab，MIT） |
| `image` | 统一编辑管线抽象（**当前未被 app 引用，见待办**） |
| `share` | 导出配置 / 导出器 / 分享 Intent |

`app` 内部：

```
com.imagedge.camera/
├── data/          # 数据层（remote=PTP 通道、ble=蓝牙快门、local=Room）
├── feature/       # 按功能分包：album connection control download edit home root settings share
├── ui/            # 设计系统：theme（含 Color/Shape/Radius）、components、glass（液态玻璃）
└── core/          # app 内的基础设施
```

## 关键约定（改动时请遵守）

- **主题**：极简黑白。浅色主色 `InkLight #1A1B1E`，深色 `InkDark #E8E9EB`。语义色只表状态，必须「图标 + 文字」双保险。
- **液态玻璃**：所有玻璃参数集中在 `ui/glass/GlassSurface.kt` 的 `GlassSpec`；背景光晕在 `ui/glass/GlassPage.kt`。
  新增可点击的玻璃组件时用 `Modifier.glassReactive(onClick)`（按压 + 拖动跟随），不要直接用 `clickable`。
- **提交信息**：英文、简洁、中性。README 用 shields.io 徽章 + 目录 + 结构化 feature 段。
- **每改必真机验证**，零崩溃才继续。

## 已知坑（务必先读，都会浪费你半天）

1. **不要给玻璃组件用 Material3 的 `Button` / `IconButton` / `Card` 再叠加玻璃** ——
   实测在这些容器上叠加玻璃层会渲染成**黑色实心块**。项目内统一改用 `Box + border + glassReactive`
   或 `GlassCard` 实现。AppButton / PageHeader / HomeBigButton 都是这么写的。
2. **真正的悬浮玻璃需要双背景源**（`RootScreen`）：`pageBackdrop` 只采集背景层供页面内玻璃用，
   `navBackdrop` 采集 NavHost 内容供导航栏折射。**两者绝不能互相包含**，否则渲染递归 → RenderThread 栈溢出。
3. **`AwaitPointerEventScope` 是受限协程作用域**，手势循环内不能驱动 `Animatable`。
   参见 `ui/glass/GlassReaction.kt` 的写法（state 快照 + `LaunchedEffect` 做回弹）。
4. **PTP 协议会话陷阱**：切换功能模式（选片集 ↔ 整卡）会 disconnect + 重连，
   重连后**原对象句柄全部失效**，进行中的下载必然失败且无法续传。
   任何 `switchFunctionMode` 前必须确认无活跃下载。
5. **整卡全量枚举代价极高**（上千对象、分批持锁），频繁重扫会与下载争抢 PTP 通道 →
   触发事务超时自愈 → 断连 → 句柄失效 → 所有下载 `0x2009` 失败。
   整卡补全只在列表为空时兜底，非空交给用户手动下拉刷新。
6. 判断断连时必须**排除 UPnP 通道**：`connectionState` 只转发 PTP 通道，
   UPnP 下恒为 `DISCONNECTED`，不排除会误杀所有 UPnP 下载。
7. **真机装的是 release 签名版**时，`assembleDebug` 会因签名不符装不上
   （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。调试统一用 `assembleRelease` + `adb install -r`。

## 待办（按优先级）

1. **`image` 模块未被采用**：`EditStep` / `ImagePipeline` 抽象已写但全项目零引用，
   `app/feature/edit` 下 5 套编辑实现（Basic / ExifFrame / LutEdit / LiveTriptych / VideoToLive）各自为战。
   建议把编辑逻辑收敛到统一管线；若不打算做，则删除该模块以免误导。
2. **app 层无测试**：现有 8 个单测都在底层模块（ptp/upnp/lut/raw/motionphoto）。
   建议先补 `DownloadViewModel` / `ConnectionViewModel` 等状态机测试（需引入 mock 库），
   再加「启动 + 导航」的 instrumented 冒烟测试，把「零崩溃」从人工验证变成自动回归。
3. **分享链路最薄**：`app/share` 373 行 + `share` 模块 444 行，端到端（导出 → 系统分享 → 相册落盘）需补验证。
4. **lint baseline 36 条**：多为 `UseKtx` 类低危项，可清理；其中 `InsecureBaseConfiguration` 1 条建议核实。

## 环境备注

- 开发机为 macOS；若你在 Linux/Windows，注意 `local.properties` 与 SDK 路径差异。
- `design/ic_launcher/` 是应用图标的**设计源**（SVG + 渲染脚本）。改图标需同步三处：
  `drawable/ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`、设计源。
