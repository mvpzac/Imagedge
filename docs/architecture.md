# Architecture 架构说明

## Module graph 模块关系

```
:app (Compose UI, MVVM + Hilt)
 ├── :core   纯 Kotlin 基础（AppLog、StreamUtils）
 ├── :ptp    PTP/IP 协议栈（ISO 15740 + Sony SDIO 扩展）
 ├── :upnp   UPnP/SOAP + SSDP
 ├── :liveview LiveView 流（裸 60152 socket）；ZV-E10 无 Camera Web API 服务，故未用 JSON-RPC
 ├── :raw    RAW 内嵌 JPEG 提取（libraw NDK 规划中）
 ├── :lut    .cube 解析 + LUT 处理器（GPU：GLES 3.0 3D 纹理；CPU 兜底）
 └── :motionphoto 视频 → 动态照片（Motion Photo / LIVE Photo）封装（Media3 MuxerUtil）
```

纯协议模块保持「无 Android 依赖、无 DI 依赖」——需要注入的实现在 `:app` 的
`injection/AppModule.kt` 中以 `@Provides` 绑定（例如 `RawDecoder`、`LutProcessor`），
替换实现只改一处。

## 连接与数据流

```
ConnectionViewModel ──▶ CameraRepository（通道路由 + 状态）
        │                     ├── PtpChannel   ──▶ PtpIpClient（:ptp）
        │                     └── UpnpChannel  ──▶ UpnpClient（:upnp）
        ▼
ConnectionStateHolder（@Singleton 共享状态：主页/设置页任一入口连接，全页面同步）
```

- **通道抽象**：`CameraChannel` 接口（listMedia/getThumbnail/download/takePicture…），
  带 `connectionState` 与 `contentEvents` 两个可选能力（默认实现），UPnP 通道零改动。
  通道还**自我声明**身份与能力（`deviceModel`/`deviceFirmware`/`supportsCapture`），
  不靠「抛异常再由调用方捕获」表达差异。
- **取景帧单点采集**：`CameraControlViewModel` 用一个 Job 把 60152 帧解码进 `frame: StateFlow`，
  嵌入预览与监看工作台都读它。**不要**改成把 cold flow 交给界面各自 collect——
  `LiveViewRepository` 是 @Singleton 只持有一个 `LiveViewClient`，两处各 collect 就是两条流打同一客户端。
- **能力模型**：`CameraIdentity(model, firmware, transport, mode)` 是能力快照的归档键，
  `CameraCapabilities` 把每项能力判为 unknown/unsupported/readOnly/writable 并保留判定依据。
  连接、断开、功能模式切换都会重建快照；参数下发只有拿到 `PropertyWriteDecision.Send`
  才触达通道。规则与未验证项见 `camera-capability-matrix.md`。
- **档案与预设（T6）**：`data/profile/` 用独立的 `profile.db` 存「这台机器上次实测能调什么」
  与「命名预设」。两条硬约定：档案键 `profileKey`（型号 + 固件）与快照键 `snapshotKey`
  （再加传输方式 + 模式）**分开**，否则一台相机会按连接方式裂成多份档案；
  存档**永不授权下发**——解出来的快照恒为 `stale`，预设应用前必须重新读 0x9209，
  且逐项以「读回一致」才算成功（`PresetPlanner`）。连接凭据不进档案、不进导出、不进日志。
- **拍摄任务状态机（T3）**：`data/capture/CaptureWorkflow.kt` 是纯函数状态机，
  ViewModel 只负责按它算出的结果发命令。两条不可让的规矩：
  **命令发出 ≠ 已拍摄**（`confirmed` 只能由相机反馈置真），以及
  **任何终态都必须交出待释放的按键**（释放清单在 `finally` 里发送，取消/超时/断线同一出口）。
  间隔拍摄按「上次完成 + 间隔」调度、忙时跳过；系统里**不存在自动重试**。
- **传输策略（T3）**：`data/transfer/TransferPolicy.kt` 存尺寸/续传/拍后自动保存偏好，
  在下载页展示；**传输范围不是偏好**——它由相册浏览模式推导，存成偏好会出现
  「prefs 写着整卡、实际连着选片集」的假信息。自动保存走 `AutoSaveLedger` 去重。
- **传输账目与批次（重构批次 C 第二刀）**：`download_task` 从「只存对象身份」变成「存整笔账」——
  状态/进度/失败原因/相册 Uri/批次号/是否已看全部落库（v2→v3 只用 `ADD COLUMN`，老行取默认值或 NULL，
  迁移实测过「装旧包造数据 → 覆盖安装新包」）。**一次提交算一批**（`TransferBatch.kt` 里
  `batchId = 库里最大值 + 1`，在入队互斥锁内分配），批次摘要与全局任务条都只说「本批」。
  重启时只有当时未完成的行才重新排队（PTP 整文件重传，没有断点），完成/失败是**账目**不是任务，
  绝不自动重下。任务行唯一的常规删除时机是用户点「清空」；「清空记录」只清 `download_history`，
  **一张照片都不删**。规则集中在 `TransferBatch.kt` 的纯函数里（有单测），`DownloadManager` 只做 I/O。
- **页面骨架与安全区域（重构批次 A）**：`ui/layout/AppScreenFrame` 是安全区域的**唯一所有者**
  （top 只给标题栏，bottom + horizontal 只给内容），`AppPageHeader` 只设最小高度、不自己吃系统边距。
  旧 `AppPage`/`PageHeader` 保留给尚未迁移的页面，但**同一页只能有一套**；
  根布局不再代任何页面补 `statusBars`，没有自带标题栏的 Tab 页各自 `statusBarsPadding()` 作适配层。
  结构尺寸取自 `ui/theme/UiSize.kt`（下限/上限，不是固定高度）。
- **导航与一级入口（重构批次 B）**：`navigation/` 包拆开原先挤在 `RootScreen.kt` 里的四件事——
  `AppDestination`（Tab 与子路由定义）、`NavigationActions`（`selectTab` / `openSubDestination` /
  `currentTab`）、`AppNavHost`（纯路由表）、`AppRoot`（跨页浮层、玻璃背景源、底栏可见性）。
  **路由字符串只出现在 `navigation/` 内**：`feature/` 页面只收回调，不知道自己挂在哪条路由上，
  也就没法自己 `navigate()` 到别页。四个一级 Tab 各带 `saveState`，切走再切回保留本页状态；
  同一个页面只允许一条进入路径（创作升为一级入口后，`edit_hub` 路由已删）。
  悬浮导航的底部让位量由 `FloatingNavBar` 实测胶囊高度后经 `LocalNavClearance` 下发，不是常量。
- **相机工作台的层级（重构批次 B）**：`feature/camera/CameraHubScreen` 的顺序是
  标题栏 → `CameraStatusCard` → 两个同级任务入口 → 仅必要时出现的引导卡。
  状态卡是**功能私有组件**（`feature/camera/CameraStatusCard.kt`），因为它要懂
  `ConnectionPhase` 与 `CapabilityState` 的四态语义；放进 `ui/components` 就会退化成
  `title: String` 参数，各调用点自己猜该填什么。它同时是本页唯一的玻璃卡（§3.2 预算）。
  能力摘要只转述 `CameraRepository.capabilities`（0x9209 派生），首页不自己探测；
  「断开」的后果说明读 `DownloadManager.hasActiveDownload`。
  设计 §6 点名的 `TaskEntry` 由 `ActionRow` 直接承担，**没有**新建同名包装组件——
  它与 `ActionRow` 的差别只有名字，多一层只会让「该用哪个」变成新问题。
- **照片页与底部条位（重构批次 C）**：`feature/photos/PhotosScreen` 是照片 Tab 的根，
  不再有「相册中枢」那一层；范围（选片集 ↔ 存储卡）是**业务动作**，走 `BrowseScopeSheet`
  切换并先确认相机通道成功才更新界面状态，传输中禁用切换但面板仍可打开看说明。
  底部同一时刻只能有一条栏：`navigation/BottomSlot` 是唯一真相，页面进入选择态时
  `claim(SelectionBar)`、离页/退出时 `release`；根上的 `bottomLayoutOf(页面占位, 传输小条, 是否在一级入口)`
  是**唯一**决定「这一帧底部画什么」的地方（设计 §8.3：一级入口是「传输小条＋导航」两层，
  选择态则两条都不画，绝不允许导航+任务条+保存按钮三层叠加）。网格格子（`PhotoGridTile`）
  **整格一个触点**，勾选标记不注册第二个点击。
- **全局传输小条（重构批次 C 第二刀）**：`feature/transfer/TransferMiniBar` 由 `AppRoot` 画在
  导航胶囊上方，内容来自 `data/transfer/TransferMiniBarStore`（纯函数 `miniBarOf` 的包装）。
  它只在**有活动任务**或**本批有尚未看过的失败**时存在，进传输页即算看过。
  只在一级入口出现：二级页没有任何东西为它预留高度，浮上去就是压住最后一行。
  它的高度与胶囊一起经 `LocalNavClearance` 实测下发，不是常量。
- **引导呈现与业务解耦**：`ui/guidance/`（`GuideCard`/`ContextHint`/`HelpSheet`）只呈现内容，
  不查权限、不连相机、不决定是否出现；出现与否由 `data/guidance/GuidanceStore` 按
  `guideId + version + 机型` 判定，且「已看过」与「任务成功过」是两条独立记录。
- **相册刷新**：事件流（`StoreAdded/Removed/ObjectAdded`）触发即时静默刷新，
  4 秒轮询兜底；`MediaSessionCache` 让相册与二级页（大图/编辑）共享列表。
- **配网与连接向导（重构批次 D）**：`feature/connection/ConnectWizardScreen` 是一条完整子流程
  （相机准备 → 手机连接 → 确认连接），扫码只是其中一步——原来它是主页上的一个半屏弹窗，
  权限框和手动 IP 又各自叠在别的层上。阶段与步骤全部由 `ConnectWizard.kt` 的纯函数
  （`stepsOf` / `exitsOf` / `hotspotAfter` / `parseManualHost`）从真实信号算出来，
  **不画百分比**，且五种走不通的情况各有一个能点的出口。
  `QrScanViewModel` → `CameraWifiManager.connectToCameraHotspot`（WifiNetworkSpecifier）这条实现未动。
  关键约束见下节。

## 线程与可靠性

- `PtpChannel` 用**可取消协程 Mutex** 串行化全部 PTP 事务（勿用 `@Synchronized` 包阻塞 IO
  ——下载 RAW 持锁数十秒会让轮询/保活全部死锁，真机教训）。
- 事务分级超时（普通 30s / 扫描 60s / 下载 600s），超时 `forceClose()` 强关 socket
  解除阻塞读，再由通道层自动重连。
- 10s `GetDeviceInfo` 保活对抗相机 ~30s 闲置踢线；失败即停防刷屏。

## 导航

```
相机 TAB ─ 状态卡 + 两个同级任务入口 + 一条「连接相机」
照片 TAB ─ 直接是照片页（范围行 / 网格 / 选择态底栏），标题尾部「传输」
创作 TAB ─ 四个工具入口（编辑调节 / 边框水印 / 视频转动态 / LIVE 三拼）
设置 TAB ─ 外观(主题) / LUT 管理 / 下载目录 / 权限与使用帮助 / 相机档案与预设 / 关于
子页     ─ 连接向导(扫码为其中一步) / 传输页 / 遥控 / 查看器 / 各编辑器
```

所有二级页面隐藏底部 TAB，左上角返回图标。四个一级 Tab 之间切换保留各自状态；
底栏画什么由 `navigation/BottomSlot.kt` 的 `bottomLayoutOf` 一处决定。

## 关键类速查

| 类 | 位置 | 说明 |
|---|---|---|
| `PtpIpClient` | `:ptp` | 双 socket 握手、索尼初始化序列、事务执行 |
| `PtpChannel` | `:app/data/remote` | 事务互斥、超时自愈、保活、事件监听 |
| `CameraCapabilities` | `:app/data/model` | 能力四态判定、选项生成、写入决策（`decideWrite`/`accepts`） |
| `CameraProfileStore` | `:app/data/profile` | 档案 / 能力快照 / 最近连接 / 命名预设的读写门面（`profile.db`） |
| `PresetPlanner` | `:app/data/profile` | 预设逐项「校验 → 下发 → 读回」判定（纯函数，七种结果状态） |
| `PresetDocument` | `:app/data/profile` | 预设导出/导入格式：SHA-256 校验和 + 有界输入 + 越界整份拒绝 |
| `ViewportTransform` | `:app/feature/control/monitoring` | 监看画面统一坐标模型：正向供绘制、逆向供触摸反查 |
| `MonitoringWorkstation` | `:app/feature/control/monitoring` | 全屏监看工作台（Dialog 独立 window，横屏 + 沉浸） |
| `SonyBleShutter` | `:app/data/ble` | 蓝牙遥控快门（配对/GATT/命令队列） |
| `CameraWifiManager` | `:app/data/remote/wifi` | 热点配网、网关发现、进程网络绑定 |
| `EmbeddedJpegDecoder` | `:raw` | ARW TIFF 解析提取内嵌预览 |
| `CubeLutParser` / `CpuLutProcessor` / `GpuLutProcessor` | `:lut` | .cube 解析 / GPU（GLES 3.0 3D 纹理）/ CPU 三线性兜底 |
