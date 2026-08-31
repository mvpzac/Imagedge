# Architecture 架构说明

## Module graph 模块关系

```
:app (Compose UI, MVVM + Hilt)
 ├── :core   纯 Kotlin 基础（AppLog、StreamUtils）
 ├── :ptp    PTP/IP 协议栈（ISO 15740 + Sony SDIO 扩展）
 ├── :upnp   UPnP/SOAP + SSDP
 ├── :liveview LiveView 流（裸 60152 socket）；ZV-E10 无 Camera Web API 服务，故未用 JSON-RPC
 ├── :raw    RAW 内嵌 JPEG 提取（libraw NDK 规划中）
 ├── :lut    .cube 解析 + LUT 处理器（CPU；Vulkan 规划中）
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
- **相册刷新**：事件流（`StoreAdded/Removed/ObjectAdded`）触发即时静默刷新，
  4 秒轮询兜底；`MediaSessionCache` 让相册与二级页（大图/编辑）共享列表。
- **配网**：`QrScanViewModel` → `CameraWifiManager.connectToCameraHotspot`（WifiNetworkSpecifier）。
  关键约束见下节。

## 线程与可靠性

- `PtpChannel` 用**可取消协程 Mutex** 串行化全部 PTP 事务（勿用 `@Synchronized` 包阻塞 IO
  ——下载 RAW 持锁数十秒会让轮询/保活全部死锁，真机教训）。
- 事务分级超时（普通 30s / 扫描 60s / 下载 600s），超时 `forceClose()` 强关 socket
  解除阻塞读，再由通道层自动重连。
- 10s `GetDeviceInfo` 保活对抗相机 ~30s 闲置踢线；失败即停防刷屏。

## 导航

```
主页 TAB ─ 连接卡片 + 扫码连接(半屏弹窗) + 遥控拍摄入口
相册 TAB ─ 中枢（零加载）：1 相册查看 / 2 相册传输(下载队列) / 3 相册编辑(LUT)
设置 TAB ─ 外观(主题) / LUT 管理 / 下载目录 / 手动 IP / 关于
```

所有二级页面隐藏底部 TAB，左上角返回图标。

## 关键类速查

| 类 | 位置 | 说明 |
|---|---|---|
| `PtpIpClient` | `:ptp` | 双 socket 握手、索尼初始化序列、事务执行 |
| `PtpChannel` | `:app/data/remote` | 事务互斥、超时自愈、保活、事件监听 |
| `SonyBleShutter` | `:app/data/ble` | 蓝牙遥控快门（配对/GATT/命令队列） |
| `CameraWifiManager` | `:app/data/remote/wifi` | 热点配网、网关发现、进程网络绑定 |
| `EmbeddedJpegDecoder` | `:raw` | ARW TIFF 解析提取内嵌预览 |
| `CubeLutParser` / `CpuLutProcessor` | `:lut` | .cube 解析 / 三线性插值 |
