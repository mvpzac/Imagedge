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
| `image` | 非破坏性编辑管线（`EditStep` + `ImagePipeline`）——**已被 `feature/edit/BasicEditViewModel` 使用** |
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

- **UI 规范**：先读 [UI-SPEC.md](UI-SPEC.md)。三条最容易踩的：① 颜色/字号/圆角/间距只能用
  `ui/theme` 的 token，`feature/` 不允许字面量；② 页面骨架走 `AppPage`（边距 16dp、可滚动、
  避让导航栏），组件从规范 §6.1 决策表里选，不要直接用裸 M3 控件；
  ③ 触控目标 ≥ 48dp、图标按钮必须有 `contentDescription`。
  **提交前跑 `./gradlew :app:uiSpecCheck`**（已并入 `check` 与 CI）：`feature/` 下出现裸
  `Button/TextButton/IconButton/FilterChip/AssistChip/OutlinedTextField/Switch/Slider/Card`
  会直接失败并指出该改用的设计系统组件。
- **主题**：极简黑白。浅色主色 `InkLight #1A1B1E`，深色 `InkDark #E8E9EB`。语义色只表状态，必须「图标 + 文字」双保险。
- **液态玻璃**：所有玻璃参数集中在 `ui/glass/GlassSurface.kt` 的 `GlassSpec`；背景光晕在 `ui/glass/GlassPage.kt`。
  新增可点击的玻璃组件时用 `Modifier.glassReactive(onClick)`（按压 + 拖动跟随），不要直接用 `clickable`。
  **玻璃等级不要自己算**：组件里读 `LocalGlassLevel.current`（由 `RootScreen` 统一提供）——
  直接调 `rememberGlassLevel()` 会让每个玻璃元素各注册一个省电模式广播接收器，
  一屏 5~10 个就是每次进页面 5~10 次 Binder 调用（切页卡顿的固定开销）。
  参数分档用 `GlassProfile.SMALL`（按钮/开关/标签：3dp/16dp）与 `CONTAINER`（卡片/导航/弹窗：8dp/24dp），
  取值对齐上游官方示例——**玻璃要"几乎看得清背后"，靠边缘折射出彩，而不是把背景糊掉**。
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
8. **配网凭据必须按 `WifiQrInfo.auth` 分支设置**（`WifiAuth.OPEN/WPA2/WPA3/WEP`）：
   无条件 `setWpa2Passphrase` 会让 `T:nopass` 的开放热点、`T:SAE` 的 WPA3 热点全部失败，
   且提示误导用户去查密码。WEP 无 specifier 通道，只能在 UI 明确引导手动连接。
9. **MediaStore 落盘必须 `IS_PENDING=1` → 写完置 0，并写 `DATE_TAKEN`**：
   否则下载中的残片会被图库看到，且照片按「下载时间」而不是拍摄时间排序。
10. **`dataSync` 前台服务在 Android 15+ 有 6 小时/天预算**：`DownloadService.onTimeout`
    必须停下前台服务并留可点击通知，否则系统会强制停应用、丢后台任务。
11. **`PtpIoException` 继承 `IOException`** 是刻意的：上层以 `IOException || PtpResponseException`
    判定「可自愈重连」。改回普通 `Exception` 会让 forceClose / 保活判死后的自动重连全部失效。
12. **Media3 `EditedMediaItemSequence` 要求同一序列各段轨道数一致**（实况图三拼踩坑）：
    对某一段用 `setRemoveAudio(true)` 而其他段有声音，会直接报
    "The preceding MediaItem does not contain any audio track…" 导出失败。
    正确做法：只要有任一段要声音就**全部保留音轨**、静音段改挂 `GainProcessor(0)`，
    并用 `experimentalSetForceAudioTrack(true)` 为「源本身无音轨」的段补静音轨。
13. **三拼/画框的转码输入必须是解析出的 MP4**（`slot.videoFile`），不是实况图 URI：
    实况图是 JPEG 头 + 后挂 MP4，Media3 会按「图片输入」处理——裁剪分数按视频尺寸算
    却作用在静态画面上，成品要么是静帧要么直接失败。

## 编辑功能现状（2026-09-11 完善后）

| 编辑器 | 能力 |
|---|---|
| 边框水印 | 5 套模板（经典白边 / 暗色底栏 / 白框悬浮 / 双行签名 / 极简叠字）、Inter 字体、两行信息层级、拍摄时间 + 自定义文字、逐字段开关、LOGO 与圆角开关、导出保留 EXIF、实况图保留动态 |
| 编辑调节（原「LUT 调色」） | 三分区：**调色**（三类滤镜 + 实时缩略图、长按对比原图、强度与曝光/对比度/饱和度/色温单遍处理）、**裁剪**（比例预设 + 可拖动裁剪框）、**旋转**（左右 90° / 水平垂直翻转 / 拉直 -45..45 自动裁角）；按原分辨率导出并保留 EXIF。下载页「编辑」与编辑中枢入口都指向它 |
| LIVE 三拼 | 三张实况图统一比例/对齐、逐段重选封面与声音开关、拼接预览 + 真机导出、结果页确认 |

> 调色算法集中在 `:lut`（`ColorAdjust` + `CpuLutProcessor`，带单测）；几何换算集中在
> `:image`（`Geometry` + `ImagePipeline`，带单测）；画框排版集中在 `ExifFrameViewModel`
> 的 render 区。改视觉/交互只需动这几处，不必碰解码与导出链路。
>
> **LUT 的两条实现路径**：`GpuLutProcessor`（默认绑定，GLES 3.0 + 3D 纹理，硬件三线性）
> 与 `CpuLutProcessor`（兜底 + 单测基准）。调色系数由 `AdjustUniforms` 统一推导，
> 改公式只改这一处。GPU 只走 `applyToBitmap` 直通路径（预览/导出），
> 小图与字节数组接口走 CPU；任何失败都会打 `CamRemote-lut` 日志并永久回退。
> 真机排查：`adb logcat -s CamRemote-lut:*` 看「GPU LUT 启用」或「回退」原因。
>
> ⚠️ **几何顺序不可随意调换**：`ImagePipeline` 固定为「拉直 → 旋转 → 翻转 → 裁剪」，
> 裁剪坐标是相对**几何之后**的画面。UI 侧旋转/翻转画面时，必须同时用
> `Geometry.rotate90 / flipHorizontal / flipVertical` 变换裁剪框，否则框会与内容错位。
> 原来的「基础调整」（`BasicEditScreen/ViewModel`）已删除——它是编辑调节的子集。

## 待办（按优先级）

1. **app 层无测试**：现有 8 个单测都在底层模块（ptp/upnp/lut/raw/motionphoto），
   `app` 连 `src/test` 目录都还没有。建议先补 `DownloadManager` / `AlbumViewModel`
   等状态机测试（需引入 mock 库），再加「启动 + 导航」的 instrumented 冒烟测试，
   把「零崩溃」从人工验证变成自动回归。
2. **编辑实现仍是 5 套并存**：`Basic` 已跑在 `:image` 非破坏性管线上，但
   `ExifFrame / LutEdit / LiveTriptych / VideoToLive` 仍各自为战。建议逐步收敛到统一管线。
3. **分享链路需补端到端验证**：`app/share` + `share` 模块（导出 → 系统分享 → 相册落盘）
   的端到端路径要真机走一遍，尤其是 PNG/WebP 无 EXIF 容器时的提示是否准确。
4. **lint baseline 36 条（app）/ 57 条（motionphoto）**：多为 `UseKtx` 类低危项，可清理；
   其中 `InsecureBaseConfiguration` 1 条建议核实。
5. **CI 只 lint `:app`**：`motionphoto` 的 57 条 baseline 从未被检查，建议改跑根任务 `lint`。

> 完整审查结论与路线图见 [REVIEW-2026-09-11.md](REVIEW-2026-09-11.md)。
> 第三方组件与许可声明见根目录 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## 环境备注

- 开发机为 macOS；若你在 Linux/Windows，注意 `local.properties` 与 SDK 路径差异。
- `design/ic_launcher/` 是应用图标的**设计源**（SVG + 渲染脚本）。改图标需同步三处：
  `drawable/ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`、设计源。
