# Changelog 更新日志

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versioning follows [Semantic Versioning](https://semver.org/).

## [0.2.0-alpha01] - 2026-09-03

> **Alpha 测试版**：一站式闭环（连接-传输-编辑-分享）的首个迭代，新增「分享」环节。功能可能变动，不建议作为日常主力版本使用。改动前已建立完整备份（`Imagedge-backup-2026-09-03-alpha`）。

### Added / 新增

- **分享（新模块 `:share`）**：已下载的照片可导出并分享到任意应用——补齐一站式闭环的最后一环
  - 尺寸档位：原图 / 2048px / 1080px / 2M（与 Sony 官方「2M 传输」同档）
  - 输出格式：JPEG / PNG / WebP（PNG 无 EXIF 容器，界面会明确提示）
  - **EXIF 隐私策略**：保留全部 / 仅清除 GPS 位置 / 清除全部信息（默认「仅清除位置」）
  - 画质调节（60–100）
  - 走系统 Sharesheet，不集成第三方 SDK、不申请新权限
- **方向归一化**：按源图 EXIF Orientation 把像素转正后再导出，相机竖拍照片分享出去不会躺着
- 下载完成的任务记录相册 Uri（仅内存流转），作为分享与编辑的入口
- **基础调整（新模块 `:image`）**：亮度 / 对比度 / 饱和度 / 色温 + 旋转 90°
  - 非破坏性编辑栈：只记录 EditStep 列表，渲染时才应用到像素，可回退、可复用、可批量套用
  - 颜色类步骤合成单个 ColorMatrix 一次绘制完成，不逐像素运算；预览走降采样，大图不卡
  - 现有 edit/ 下的 LUT、EXIF 边框、三格图、视频转 Live Photo **未改动**，后续再逐步统一到这条管线

### Changed / 变更

- 下载队列：已完成的任务新增「编辑」与「分享」按钮

## [0.1.9] - 2026-09-01

### Changed / 变更

- **整卡照片按最新优先加载**：对象句柄枚举改为倒序，进入整卡后先显示最近拍的照片、老照片逐批补上，不再先灌满整屏老照片、最新的迟迟不出来（显示顺序仍按拍摄时间倒序排列）

## [0.1.8] - 2026-09-01

### Changed / 变更（下载回归稳定路径）

- **移除分块断点续传，回归整文件下载**（稳定性优先）：0.1.5 引入的 `GET_PARTIAL_OBJECT` 分块下载在真机（ZV-E10，整卡 ContentsTransfer 模式）实测被相机以 `0x2009（无效对象句柄）` 拒绝——该机型在此模式下不支持分块读取，导致所有下载失败；0.1.3 的整文件下载（`GetObject`）在同一环境实测稳定。分块的协议实现保留在 `:ptp` 与 `CameraRepository` 中，待确认机型支持范围后再评估按机型启用。中断后需整体重下（单文件 25MB RAW 实测 ~6s，可接受）
- **PTP 扫描不再触发 forceClose 自愈**：扫描是只读操作，超时只需重试，不应杀连接——连接一断会话句柄全废，殃及后续所有下载
- **枚举超时放宽**：整卡 `GetObjectHandles` 要遍历整张 SD 卡建立索引（上千对象），单次调用常超默认 30s；改用专用 120s 超时，让慢枚举完整跑完
- **保活区分「忙」与「断」**：长事务占锁时保活等锁超时不再误判为断连（原先会停掉保活协程 → 相机 30s 无活动真踢线，形成「扫描越久越容易断线」的恶性循环）；`listMedia` 遇忙超时也不再自动重连（重连会重建会话、句柄全换）

### Fixed / 修复（整卡查看，真机 ZV-E10 + OnePlus）

- **整卡下载被中断**：退出整卡页 5 秒后无条件切回选片集，而切换功能模式会断开并重连 PTP 会话——会话重建后对象句柄全部失效，相机对下载回 `0x2009（无效对象句柄）`，刚开始的下载批量失败且无法续传。现在退出整卡只登记请求，等下载队列空闲后再切换（2s 轮询，10 分钟超时兜底）；重新进入整卡会撤销待处理的退出请求
- **整卡照片加载不全**：整卡原本是一次性快照、不轮询，首次枚举若残缺则永不自愈，用户只能反复手动点重试。现在列表为空时做有限次静默补全校验（4 次 × 8s），扫描命中静默期改为等待结束而非直接返回空列表，内容集为空的重试窗口放宽到 2s / 3s / 5s；下载进行中一律不扫描（避免与传输争抢 PTP 通道）
- **下载任务无法手动关闭**：新增取消能力——取消按钮（单个任务）与「全部取消」（顶栏）。排队中的任务在出队时跳过，下载中的任务取消其协程；用户主动取消不写入传输历史
- **断连后任务空转**：连接断开时对象句柄已失效，下载入口直接失败并提示「相机连接已断开，请重新连接后再下载」（仅 PTP 通道判定，UPnP 无状态跟踪故排除）

## [0.1.7] - 2026-09-01

### Changed / 变更（UX 极简黑白改版）

- **New launcher icon「光蚀」**: minimal abstract mark — a light ring (aperture) with an eclipse color block — built strictly from the theme palette (Ink `#1A1B1E` background, `#E8E9EB` block, `#FAFAFB` ring); reimplemented as a vector adaptive icon (solid-color background + drawable foreground, monochrome layer redrawn to match), replacing the full-bleed bitmap; design sources & previews in `design/ic_launcher/`
- **Minimal black-and-white theme**: light-gray background + near-black primary, dark mode retained; the 6-tier brand color picker and dynamic color extraction are removed — the appearance section now only has the theme mode switch (light / dark / system). Default theme for new installs is light
- **Soft rounded-corner scale**: 8 / 12 / 16 / 20 / 28 dp + capsules across the whole app; every hard-coded corner, right-angle grid cell and stray `CircleShape` removed
- **Shared component layer** (`ui/components`): `AppButton` (primary/secondary/text), `IconBadge`, `EntryCard`, `EmptyState`, `ProcessingView`, `ResultMessage`, `StatusBanner`, `StepsGuideCard` — replaces 20+ duplicated style assemblies across album / download / control / edit screens
- **Motion tokens**: two spring levels + durations centralised in `ui/theme/Motion.kt`; nav springs unified
- **Scenario guidance**: three-step "connect your camera" guide card on the home screen (disconnected / error states), unified disconnect banner on the remote-shooting page, download empty state gains a "go to album" action
- **Haptic feedback**: `Haptics` singleton with four trigger classes (selection tick / action thud / switch click / error double), an in-app toggle in Settings, and respect for the system touch-feedback setting; wired into album multi-select, filters, shutter / video record, save / export, and connection success / failure

### Fixed / 修复（全量代码审查，27 项）

- **Lint**: `:motionphoto` 的 56 项存量错误（Media3 `UnstableApi` 未标注等，移植代码固有）入 lint baseline，与 `:app` 既有约定一致；根任务 `lint` 恢复全绿
- **Data loss**: a resume-download that finished but failed to commit to the gallery no longer reports success and deletes the temp file — the task is marked failed and retryable
- **LUT editor concurrency**: non-cancellable filter jobs were trampling shared pixel buffers; processing is now serialised with a `Mutex`, state writes are atomic (`update {}`), and exporting re-renders at full resolution while the interactive preview runs at ~640 px
- **Motion Photo packaging**: MPF entry size now includes the MPF segment, UltraHDR XMP merge targets the correct segment, top-level JPEG EOI is located by marker structure instead of a raw `FFD9` scan, large MOVs are rewritten with bounded memory, temp directories are cleaned up, and the OPlus timestamp follows the user-selected cover
- **Album thumbnails no longer go permanently grey** after a memory-trim (thumbnail cache generation), and the triptych preview no longer stays stale when the user edits during rendering
- **BLE**: scan callback permission guard, disconnect stops scanning, pairing receiver double-unregister guarded; PTP: socket leak on mid-handshake failure fixed, `DeviceInfo` array counts bounded
- Plus lint cleanup (0 errors), CI now runs unit tests + lint, and unit tests added for `lut` / `raw` / `motionphoto`

## [0.1.6] - 2026-08-31

### Added / 新增

- **EXIF camera-parameter frame (边框水印)**: stamp a photographer-style frame onto any photo — 4 built-in templates (floating polaroid, classic white bar, dark bar, minimal) auto-filled from the image's own EXIF (model, focal length, aperture, shutter, ISO, exposure compensation, date, etc.) with manual override for stripped metadata (`ExifFrameViewModel` / `ExifFrameScreen`)
- **LIVE-photo triptych (三拼 LIVE 图)**: pick up to 3 LIVE photos/videos, choose aspect (16:9 / 1:1 / 4:5) and per-slot cover / audio / order, then stitch into a single motion photo (`LiveTriptychViewModel` / `LiveTriptychScreen`, `VideoStitcher`)
- **EXIF preservation on export**: exported LIVE photos now keep the source image's original EXIF (make/model/timestamps/exposure) via `MotionPhotoExifPreserver`, and the album timeline shows the source shoot time instead of the export time
- **Brand logos are now user-maintained PNGs**: all 25 brands ship as transparent-background PNGs in `assets/brand_logos/`; official badge-style logos (GoPro black / realme yellow) are excluded from dark-background white tinting so they keep their native colors

### Fixed / 修复

- **11 MB photos only showed the top strip**: the fd-based sampler reused one `FileDescriptor` for both bounds probing and real decode — the second read started at a shifted offset; now the fd is reset with `Os.lseek(fd, 0, SEEK_SET)` before decoding
- **Portrait photos rendered landscape**: `BitmapFactory` ignores EXIF orientation, so every decode path now applies `rotationDegrees` via a `Matrix` (ImageDecoder path already applied it)
- **Stream fallback never ran**: `decodeStream` always returns `null` when `inJustDecodeBounds = true`, so using its return value as an "opened" check disabled the stream fallback; the flag is now set explicitly inside the stream block
- **Brand icon not vertically aligned with the parameter text**: the logo used to bottom-align to the text baseline (~14% of bar height too high); both the logo and the text now center on the bar's true vertical center using real `FontMetrics`
- **30-item stability pass (P0/P1/P2)**: BLE GATT slot leaks, dead-locked keep-alive vs business lock, non-interruptible blocking I/O, hardcoded `MainExecutor` in the QR analyzer, MediaStore garbage files on failed writes, OOM on video downloads, event-listener self-heal, Wi-Fi provisioning timeouts, and more — see `docs/修复进度_HANDOFF.md` for the per-file breakdown

### Changed / 变更

- Brand logo rendering moved from bundled VectorDrawables + 9 PNGs to 25 user-maintained PNGs only (`detectBrand` now maps every brand to `assets/brand_logos/<brand>.png`)

## [0.1.5] - 2026-08-30

### Added / 新增

- **Capability-driven exposure controls**: ISO / aperture / shutter selectors now read the camera-reported `supported` enum table (`0x9209 SDIO_GetAllExtDevicePropInfo`) and present a label → raw-value dropdown; the camera's current value always shows even when outside the preset list
- **Resumable partial download**: large files download via `GET_PARTIAL_OBJECT (0x101B)` + `SDIO_GET_PARTIAL_LARGE_OBJECT (0x9219)` with 64-bit offset split; `RandomAccessFile` writes resume from the last committed byte and retry with exponential backoff (1s → 15s cap, up to 5 attempts)

### Fixed / 修复

- **ISO "Auto" could not be set**: the old selector mapped the "Auto" label to raw `0x00000000`, which the camera rejected; now `Auto` maps to `0x00FFFFFF` (the Sony "Auto/Invalid" sentinel) so it applies correctly
- Exposure selectors now operate on raw protocol values instead of fragile string labels, eliminating encode/decode drift

### Changed / 变更

- `CameraRepository` / `CameraControlViewModel` ISO / aperture / shutter APIs switched from `String` to raw `Long` values end-to-end
- Docs aligned to the actual module graph: `:webapi` → `:liveview` rename reflected, `:motionphoto` module documented, Motion Photo export listed in Features + Roadmap

## [0.1.4] - 2026-08-30

### Added / 新增

- **Video → LIVE Photo (Motion Photo) export**: pick one or more videos, auto-extract a cover frame, package as a single-file motion photo readable by Google/OPPO/Xiaomi galleries (`:motionphoto` module, Media3 `MuxerUtil`)
- **Permission system**: runtime permission requests on first launch (camera / notifications / nearby Wi-Fi / Bluetooth / location), top-banner explanation when a required permission is missing, and a new Settings → Permissions page listing every permission with its purpose and grant state
- **Transfer history**: every completed/failed download is recorded (path, start/end time, source camera); long-press an entry on the download page for details
- **LUT type system**: `.cube` files are classified by applicable picture profile (creative / S-Log2 / S-Log3); declaring the type after import decides which row it appears in on the LUT page

### Changed / 变更

- Album Edit is now a hub: 「视频转 LIVE 图」 and 「LUT 滤镜」 sit side by side
- Home page connection buttons are larger, with a short description under each title
- **Built-in LUT set replaced**: the old 10 were S-Log3 conversion LUTs that greyed out ordinary sRGB photos. Now 8 creative looks (EditClips, free to use) + S-Log2/S-Log3 conversion LUTs generated in-house with colour-science
- LUT page: filters grouped into three rows by type, page is scrollable, explanation moved into a question-mark overlay (blurred backdrop, tap anywhere to dismiss)
- LUT import consolidated into Settings → LUT management (with delete confirmation)

### Fixed / 修复

- `POST_NOTIFICATIONS` was declared but never requested at runtime — download notifications never showed on Android 13+
- LUT page save button was pushed off-screen after filters were split into three rows

### Reduced / 体积

- Fonts subset: Smiley Sans 2.5MB → 7.6KB, Inter ×3 1.2MB → 114KB (~1.8MB smaller APK)
- Built-in LUTs 11.8MB → 9.3MB

## [0.1.3] - 2026-08-30

### Added / 新增

- Remote switching of the shoot mode ("照相模式", `0x500E`): P/A/S/M/AUTO selectable on the phone (via `0x9205`, white-list ∩ camera-reported enum)
- Album: 「选片集」/「整卡」 split into two independent entries on the Album tab; grid grouped by capture date (sorted by filename within each day); photo / video / RAW filters; file-format badge (JPG/ARW/MP4) on the top-left
- Download queue thumbnails
- App icon rebuilt from the provided artwork (eliminates the adaptive-icon black border)

### Changed / 变更

- Low-light QR scanning: 2× digital zoom (fixes overexposure of the camera screen) + Otsu binarization + inverted fallback + TRY_HARDER
- Global toast moved from bottom Snackbar to a top slide-in banner (auto-dismisses in 2.5s)

### Fixed / 修复

- QR pairing connection drop: duplicate QR frames re-triggered provisioning after success
- Full-card mode not switching back on exit (delayed task cancelled itself inside `switchFunctionMode`)

## [0.1.2] - 2026-08-29

### Added / 新增

- Extended remote parameters based on official protocol (reverse-engineered value tables):
  - White balance selector (`0x5005`, official enum values: Auto/Daylight/Shade/Cloudy/Incandescent/Fluorescent/Flash)
  - Exposure compensation selector (`0x5010`, INT16 EV×1000, ±3.0EV in 1/3 steps)
- Read-only display of the camera's shoot mode ("照相模式") via `0x500E ExposureProgramMode` with official naming (M/P/A/S/AUTO/STILL/MOVIE)

### Fixed / 修复

- Parameter two-way sync: camera-side dial/menu changes now reflect on the phone (~1s, via `0xC203`/`0x4006` property-changed events with 300ms debounce)

### Changed / 变更

- QR scanner sheet: removed spinner overlay in the viewfinder; status is shown by the text line only
- Remote shooting screen is now scrollable (parameters no longer cut off on small screens)
- Parameter selectors always show the camera-reported current value, even when it is not in the preset list

## [0.1.1] - 2026-08-29

### Changed / 变更

- 64-bit only: ARM64 (arm64-v8a) and x86_64 ABIs; 32-bit devices are no longer supported
- Material 3 refresh: violet brand palette, 7-step typography with Inter, unified shape tokens, skeleton loading / empty states / global snackbar, dynamic color toggle (off by default)
- QR pairing flow rework: bottom-sheet scanner, stable layout, auto-connect after scan
- Connection & copy: guides now reference the actual camera entry points (「智能手机连接」 / 「发送到智能手机」)

### Removed / 移除

- Sony Camera Web API channel (JSON-RPC / SSDP discovery): ZV-E10 exposes no such service; the `webapi` module was merged into `:liveview` (raw 60152 stream only)
- Dead "shoot mode" control that silently failed on ZV-E10

### Fixed / 修复

- Live view regression after Web API cleanup (mandatory `/liveviewstream` query string restored)
- CameraX viewfinder offset when the scanner sheet is reopened (bind after first layout)
- Wi-Fi re-provisioning race on quick disconnect → reconnect

## [0.1.0] - 2026-08-28

First public release. 首个公开发布版本。

### Added / 新增

- Wi-Fi transfer: camera-side-selection driven album with event-driven refresh, thumbnails, fullscreen viewer with paging, batch download queue, MediaStore / custom-directory output
- RAW (ARW) embedded full-size JPEG preview extraction (pure-Kotlin TIFF parser)
- Bluetooth remote shutter (Sony "Bluetooth Remote" GATT protocol): pairing, two-stage shutter, record toggle
- Live view streaming with JPEG-frame extraction (firmware framing tolerant)
- QR Wi-Fi provisioning for Sony `W01` pairing QR codes; manual IP fallback
- LUT color grading: built-in S-Log3 film-simulation presets, `.cube` import/export/delete, strength blending (CPU trilinear)
- Material 3 UI, dark-first theming with light/system options
- Protocol stack modules: PTP/IP (pure Kotlin), UPnP/SOAP, Sony Camera Web API, with keep-alive, transaction timeout self-healing and auto-reconnect
