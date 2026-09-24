# Changelog 更新日志

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

> 相机能力模型（T0）、本地监看工作台（T1）、相机档案/参数预设（T6）与拍摄回传工作流（T3）：
> 参数可调性由相机描述符决定，监看侧新增全屏工作台，档案侧持久化「这台机器实测能调什么」，
> 拍摄侧把快门建成显式状态机。

### Added / 新增

**拍摄与回传工作流（T3）**

- 新增 `data/capture/CaptureWorkflow.kt`：显式拍摄任务状态机
  （倒计时 → 触发 → 相机确认 → 等文件 → 终态），全部纯函数
- 新增自拍倒计时（0 / 3 / 5 / 10 秒）与取消：倒计时阶段一个命令都不发出，
  取消它就是「什么都没发生」，提示也与「取消了一次拍摄」分开
- 新增间隔拍摄（2/5/10/30 秒 × 5/10/30 张）：按「上次完成 + 间隔」调度，
  相机忙时**跳过这一张**而不是补拍，不会攒出一串待发快门
- 新增 `TransferPolicy` 与 `TransferPolicyStore`：尺寸/格式、续传方式、拍后自动保存偏好；
  下载页新增传输策略摘要，含「缩图不减少传输量」「分块续传未实测故不启用」两条限制说明
- 相册页新增传输范围一行（选片集 / 整卡），选片集额外说明「该范围由相机决定，
  不等于相机上的全部照片」
- 新增 `AutoSaveLedger`：拍后自动保存的持久化去重账本（有界，超出上限丢最旧）
- 单测 23 项：倒计时取消不拍照、终态必交还按键、已发送不等于已确认、
  30 张间隔无重叠（含「只按时钟排期就会重叠」的反证）、失败即终态不自动重试

**相机档案与参数预设（T6）**

- 新增 `data/profile/`：独立的 `profile.db`（Room v1，不挂进 `download.db` 的迁移链）存
  相机档案、能力快照、最近连接与命名预设。档案键 `profileKey`（型号 + 固件）与快照键
  `snapshotKey`（再加传输方式 + 功能模式）刻意分开，一台相机不会按连接方式裂成多份档案
- 新增 `CapabilitySnapshotCodec`：能力快照的持久化编解码。**解出来恒为 `stale`**，
  且只恢复 `DEVICE_PROP_DESCRIPTOR` 证据的条目——通道自我声明的遥控拍摄随连接消失，
  不能由历史档案授权
- 新增 `CameraProfileStore`：连接即建档、探测成功即落快照、预设按档案归属；
  删档案连带清掉它的能力快照与预设。`recent_connection` **不含 SSID 与任何凭据**
- 新增 `PresetDocument`：预设导出/导入格式。payload + SHA-256 校验和，格式版本不符、
  校验和被改、体积/条数越界、含未知参数项一律**整份拒绝**并给原因；类型里没有可放凭据的字段
- 新增 `PresetPlanner`：预设逐项「校验 → 下发 → 读回」判定（纯函数，七种结果状态）。
  **只有读回一致才算 `APPLIED`**——相机回了 OK 却没真的改，记成成功比记成失败更坏
- 新增 `feature/profile/`：档案页（当前相机 / 参数预设 / 相机档案 / 最近连接四块），
  含命名预设的存/用/改名/删、SAF 导入导出、应用后的逐项部分失败报告，
  以及按 (传输方式, 功能模式) 归档的四态能力展示
- 单测 31 项：编解码往返 6、导出文档与越界拒绝 13、逐项规划器 10、能力模型新增 2

**监看工作台（T1）**

- 新增 `feature/control/monitoring/`：横屏 + 沉浸的全屏监看工作台，含镜像、90° 步进旋转、
  三分/四分构图网格、16:9 / 17:9 / 4:3 / 3:2 / 1:1 / 2.35:1 比例标记、双指放大与拖动、
  暂停取景、取景截图与工具栏
- 新增 `ViewportTransform` 统一坐标模型：绘制与命中测试共用同一分解与平移夹取，
  并提供 `viewportToFrame` 逆变换，为 T4 触摸对焦预留坐标基础
- 新增 `MonitoringSettings` 及其存储：镜像/旋转/网格/标记跨横竖屏与重启保留；
  缩放与平移属会话态，每次进入工作台回到 1× 居中
- 新增 `ViewfinderSnapshotWriter`：取景帧独立存入 `Pictures/ImagedgeViewfinder`，
  带 `VIEWFINDER_` 前缀、`DESCRIPTION` 说明与实际宽高元数据，标明它是预览信号而非相机原片
- 新增 4 个 Lucide 图标（水平镜像 / 暂停 / 播放 / 最大化）；网格与比例标记用自描述文字标签，
  不用含义模糊的图标
- 单测 43 项：坐标模型 29（含四角表、往返一致性、锚点与铺满约束）、标记几何 9、偏好解码 5

**能力模型（T0）**

- 新增 `CameraIdentity(model, firmware, transport, mode)` 与 `CameraCapabilities`：每项能力
  判为 unknown / unsupported / readOnly / writable，并保留判定依据（属性码、证据类型、诊断说明）。
- 遥控页展示相机身份（型号 · 固件 · 连接方式 · 功能模式），并逐项说明参数为什么不可调整。
- `:ptp` 描述符解析支持 FormFlag=0x01（Range：min/max/step），并按 dataType 提供符号扩展。
- 新增 `docs/camera-capability-matrix.md`：判定规则、属性码登记表、通道能力与未验证项。

### Changed / 变更

- 取景帧改为 ViewModel 内的**单一采集 Job**：此前是交给界面 collect 的 cold flow，
  嵌入预览与监看工作台各自 collect 会同时开两条 60152 流打同一个 `@Singleton LiveViewClient`。
- 嵌入预览与工作台复用同一套 `drawFrame`/`drawMarkers`，两处的旋转/镜像不会各自漂移。
- 放大是显示级数码放大，明确**不是**相机变焦；网格与比例标记只是叠加层，
  不改预览像素，也不进入取景截图。

- **移除全部硬编码可写档位表**（ISO / 光圈 / 快门 fallback 预设、7 档白平衡、19 档曝光补偿）。
  这些档位从未与相机核对过：f/3.5-5.6 套头上会出现 f/1.8，白平衡与曝光补偿也是照抄值表。
  现在相机没上报取值形式就禁用该项并说明原因，不再猜。
- 参数下发拆成「决策 + 执行」：只有 `PropertyWriteDecision.Send` 才触达通道，属性码与值宽度
  均取自相机描述符（此前按属性码硬编码 2/4 字节）。
- `CameraSettings` 只保留当前值，能力信息迁往 `CameraCapabilities`；曝光补偿统一为已符号扩展的
  INT16（此前 `-0.3EV` 依赖调用方自行按 16 位补码解释）。
- 断线（保活失败 / 事务超时自愈 / 用户断开）立即把界面拉回「未知」，不再继续显示上一轮的可写档位。

### Fixed / 修复

- **BLE 快门序列可能把快门留在按下状态**：`shutterDown/shutterUp` 是平铺的 fire-and-forget
  launch，离页或取消会打断序列，而释放命令在未连接时被静默丢弃。现在释放清单由状态机算出、
  在 `finally` 里发送，且 `leaveScreen()` 先取消拍摄再断 GATT（顺序反了就发不出去）。
- **超时后不再谎报「已拍摄」**：原先 3 秒等不到相机 `shutter=true` 也照样显示已拍摄。
  现在区分「命令已发出，等待确认」与「相机已确认」，超时如实落未确认并提示检查相机。
- **拍后自动保存不再重复落盘**：下载队列原有去重只覆盖进行中的任务，`CaptureComplete` 与
  内容事件各来一次就会把同一张照片写两遍（`downloadToGallery` 是无条件 insert）。
- **`GlassCard` 内容整块不可见**：内层 `Surface` 用 `Modifier.matchParentSize()`，而它是外层
  Box 的唯一子节点——`matchParentSize` 不参与父级测量，Box 量出 0 高，内容被裁光。
  主页状态卡与遥控页整块参数区都受影响，页面表现为空白且不报错。API 37 模拟器实测发现。
- **预设/快照写入后列表不刷新**：`observeAll()` 只跟踪 `parameter_preset` 一张表，
  在 `map` 里逐行查子表的写法永远不会被 `parameter_preset_item` 的写入唤醒
  （数据在库里，界面显示空）。改用 `@Transaction` + `@Relation`，同时去掉 N+1 查询。
- **禁用态的主按钮看起来是启用的**：`AppButton` 两条绘制路径都不看 `enabled`，
  灰不掉的按钮等于「按了没反应」。现在统一按 0.38 内容不透明度降级，
  与 `AppChip` / `AppLink` / `AppIconButton` 同一口径。
- 暂停取景与退到后台真正**停止采集**（取消 Job、关闭 socket），而不是只冻住渲染：
  只停渲染时相机仍在推流，射频与耗电都没有省下来。
- `AppIconButton` 新增可选 `tint`：图标色原先写死取主题 `primary`（浅色主题下近黑），
  压在纯黑监看背景上完全不可见。既有调用点行为不变。

- **请求超时不再被当成「不支持」**：0x9209 读取失败时能力为 UNKNOWN 且标记陈旧，
  下一次成功读取即恢复；陈旧快照一律禁止下发命令。
- BLE 相机状态（对焦/快门/录像）改为三态：断线后显示「未知」而不是伪造的「未录像」——
  相机可能仍在录制，只是已断开看不见。
- UPnP（「发送到智能手机」）通道下不再让遥控快门按下去毫无反应，明确提示该通道不支持遥控拍摄。

## [0.2.0-alpha06] - 2026-09-23

> 全面安全、稳定性与流畅性加固；液态玻璃渲染热路径优化。

### Security / 安全

- 加固 PTP/IP 包长、事务 ID、数据阶段与对象大小校验，所有相机下载改为有界流。
- UPnP 仅允许私有/链路本地目标及同源控制 URL，禁用重定向与凭据 URL；XML 解析限制大小、深度、节点数并阻止 XXE。
- Motion Photo 全链路改用 `Long` 与文件流，限制 XMP/媒体大小，阻止恶意 XML 和大文件内存耗尽。
- 发布日志脱敏；CI 固定 Action 提交 SHA，并新增依赖校验、SBOM、依赖审查和密钥扫描。

### Fixed / 修复

- 修复下载超时仍在后台继续、数据库幽灵任务、取消/重试竞态、断线状态错误和进度刷新过密。
- 修复 PTP 断线死锁、Wi-Fi 过期回调、BLE 扫描/GATT 生命周期泄漏及页面离开后仍保持连接。
- 修复照片编辑渲染竞态、取消泄漏、Bitmap 生命周期和 GPU 资源销毁顺序。
- 视频缓存改为内容哈希、原子写入、长度校验与 LRU 清理；运行时权限改为按功能请求。
- 移除 Lint 基线并清零有效 Lint 问题，新增协议、XML、大文件及有界流回归测试。

### Performance / 性能

- 玻璃画刷与高光 Shader 改为绘制缓存，拖动期间不再重复创建协程。
- 小尺寸玻璃控件跳过高成本景深/色散，底部导航避免重复抓取和第二次玻璃背景绘制。
- 设计缩放不再缩小触控目标，并使用窗口尺寸响应式计算。

## [0.2.0-alpha05] - 2026-09-11

> 液态玻璃效果对齐上游最佳实践 + 切页性能优化。

### Changed / 变更（玻璃）

- **参数对齐官方示例（Kyant0/AndroidLiquidGlass 的 LiquidButton / LiquidBottomTabs）**：
  模糊 `14dp → 8dp`（小元素 `3dp`）、折射位移 `46dp → 24dp`（小元素 `16dp`）、
  表面色 `0.2 → 0.18`（小元素 `0.12`），并把效果顺序改为官方的
  **vibrancy → blur → lens**（原来是 blur 在前）
  - 原参数的问题：模糊过强会把背景糊成一团色块（观感像磨砂板），折射位移过大则边缘拉伸失真。
    官方口径是「**几乎看得清背后**，靠边缘折射出彩」——这才是液态玻璃与半透明板的分界线
- **新增尺寸档位 `GlassProfile`（SMALL / CONTAINER）**：48dp 的按钮与全宽导航胶囊对同一组参数的
  观感完全不同，小控件必须用更轻的模糊（官方 LiquidButton 用 2dp/12dp，导航用 8dp/24dp）
- **按钮改用真玻璃**：此前按钮是「透明背景 + 描边」的伪玻璃（当年在 M3 `Button` 上叠
  drawBackdrop 会渲染成黑块，只能降级）。现在按钮本身已是 `Box` 实现，
  改走 `glassSurface` → 按钮终于会折射背景
- **背景素材增强**：光晕不透明度 +40%、半径 0.65 → 0.55（更明显的明暗过渡）。
  玻璃变轻后，背景没有起伏就折射不出层次——这也是「玻璃看不出来」的一半原因
- **按钮统一为浅色玻璃**：主按钮不再用主色（近黑）做表面色——那会把玻璃压成一块灰板，
  与相册页卡片的浅色玻璃割裂。现在按钮与 `EntryCard` 使用**同一个表面色（surface）**，
  主/次的层级只靠描边粗细与文字色区分（用户明确要求与相册页一致、不要黑色底）

### Fixed / 修复（切页卡顿）

- **玻璃等级改为全局下发**（新增 `LocalGlassLevel`）：原先每个玻璃组件各自调
  `rememberGlassLevel()`，而它会**注册一个省电模式广播接收器**——一屏 5~10 个玻璃元素
  就是每次进页面 5~10 次 Binder 注册/反注册。现在只在 `RootScreen` 算一次
- 模糊半径减半 + 折射位移减半本身就是最大的 GPU 收益（每帧的离屏模糊/折射开销）
- **真机实测（OnePlus 2602BRT18C）**：切换 Tab 与打开二级页共 5668 帧，
  **掉帧率 0.03%、50 线 7~9ms、99 线 23~25ms**；改造前同一台机器上采到的切换帧为
  50 线 61ms / 90 线 89ms（样本较小，但方向一致）

## [0.2.0-alpha04] - 2026-09-11

> P0 缺陷修复（依据 `docs/REVIEW-2026-09-11.md` 的审查结论）+ 编辑三件套完善 + UI 规范化。

### Fixed / 修复

- **扫码配网在开放热点 / WPA3 上必失败**：原先无条件 `setWpa2Passphrase(password ?: "")`，
  `WIFI:T:nopass` 的开放热点与 `T:SAE` 的 WPA3 热点都会以「空密码走 WPA2」发起请求并失败，
  提示还把责任推给用户的密码。现按二维码声明的认证方式分支（OPEN 不设凭据 / WPA2 / WPA3-SAE），
  WEP 明确告知 Android 不支持自动配网并引导手动连接；缺少密码的二维码直接给出可读原因，
  不再白等 15s 超时
- **下载落盘补 `IS_PENDING` 与拍摄时间**：下载中的残片不再对图库可见；`DCIM/Imagedge/`
  下的照片按拍摄时间排序（写入 `MediaStore.DATE_TAKEN`），不再按下载时间错序
- **MIME 类型补全**：HEIF（`.heic/.heif/.hif`）、TIFF（`.tif/.tiff`）、DNG、`.m4v`
  此前会落成 `application/octet-stream` 导致图库不索引
- **Android 15+ 前台服务超时**：`DownloadService` 实现 `onTimeout`，达到 dataSync
  6 小时预算时撤下前台状态并留「传输已暂停」通知，不再被系统强停
- **传输通知可点击**：进度/完成/暂停通知都带 `contentIntent`，可直接回到应用；
  全部任务结束时用 `STOP_FOREGROUND_DETACH` 保留完成态通知（原先 `stopSelf` 会把通知一起撤掉，
  用户看不到「传输完成」）
- **大图查看器潜在崩溃**：`gridPreview(item)!!` 的二次取值改为局部变量，
  消除缓存被内存回收清空时条件为真、取值为 null 的 NPE 窗口
- **玻璃降级不响应省电模式**：`rememberGlassLevel()` 监听
  `ACTION_POWER_SAVE_MODE_CHANGED`，开启省电后立即退回普通表面（原先注释声称可感知，实际不生效）
- **保活判死后回收 socket**：断开判定成立时一并 `forceClose`，避免死连接长期占用两个 fd；
  `PtpIoException` 改为继承 `IOException`，让 forceClose 后仍能走自动重连路径
- `ExportManager.inSampleSize` 删除重构残留的死分支；`PtpChannel` 的保活与事件监听
  统一到会话级协程作用域

### Added / 新增

- `THIRD_PARTY_NOTICES.md`：第三方组件、代码移植（MotionPhotoLab，MIT）、协议参考
  （alpha-fairy / alpharemote / furble / libgphoto2 / libptp 等）与资产来源的完整声明
- `docs/REVIEW-2026-09-11.md`：完整代码审查报告与分阶段路线图

### Changed / 变更（相册编辑三件套）

- **UI 规范化（第一步：立标准 + 补基础组件）**：
  - 新增 [docs/UI-SPEC.md](docs/UI-SPEC.md)：五条设计原则（内容优先 / 一屏一个主操作 /
    状态可见 / 结构可预测 / 默认可达）、对标学习（Material 3、Apple HIG、WCAG 2.2
    与 Sony Creators' App、DJI Mimo、Lightroom、Google Photos、Apple Photos、Halide、Snapseed）、
    token 全量定义、布局/组件/状态/无障碍/文案/动效规范与提交前清单
  - 补齐缺失的设计系统组件：`AppPage`（页面骨架：统一 16dp 边距 + 滚动 + 导航栏避让）、
    `AppSection`（区块标题 + 尾部动作）、`AppChip` / `AppChipRow`（选项，含 48dp 触控）、
    `AppSlider`（参数滑条）、`AppTextField`、`AppSwitch` / `AppSwitchRow`、`AppLink`
    （行内动作）、`AppDivider`
  - `PageHeader` 返回钮触控目标 36dp → **48dp**（视觉尺寸不变），符合无障碍下限
  - 按规范迁移「编辑调节」「边框水印」「LIVE 图三拼」「编辑中枢」「下载页」：
    统一页面骨架与边距、选项/参数/输入/开关/行内动作全部改走设计系统组件、
    编辑页色彩全部改为 token（沉浸层遮罩用 `ViewerBackdrop` / `OnViewer`）
  - 结果：`feature/` 下裸 M3 控件从 46 处降到 17 处（另 8 处在设计系统内部实现），
    页面左右边距从 6 种取值收敛为 16dp 一种

- **UI 规范化（第二步：迁移收尾 + 防回退检查）**：
  - **`feature/` 下裸 M3 控件清零（46 → 0）**：设置页 7 处文字动作、首页输入框与文字动作、
    遥控页容器与按钮、看图页 3 个按钮与返回钮、视频转 Live 的窗口长度选项/时间轴滑条/关闭钮、
    导出设置面板的尺寸/格式/元数据选项与画质滑条、相册页筛选、扫码页重试、
    下载页历史弹窗与取消任务的图标按钮，全部改走设计系统组件
  - 新增 `AppIconButton`（48dp 触控 + 半透明圆底，替代裸 `IconButton`），补齐
    `AppChipRow` 的单项禁用、`AppSlider` 的离散档位 / 自定义数值文案 / 拖动结束回调
  - 新增 `ArrowUp` / `ArrowDown` 图标（三拼重排序用），避免用文字箭头污染排版
  - **新增 UI 规范静态检查 `./gradlew :app:uiSpecCheck`**：扫描 `feature/` 下的裸 M3 控件并
    输出「文件:行号 + 应改用的组件」，已并入 `check` 任务与 CI（位于 lint 之前）；
    已用注入违规的方式验证其确实会失败

- **LUT 上 GPU（OpenGL ES 3.0 + 3D 纹理）**：
  - 新增 `GpuLutProcessor`：把 3D LUT 上传为 `GL_RGB16F` 的 `sampler3D`，插值交给纹理单元的
    **硬件三线性过滤**（质量不低于 CPU 的三线性插值，还省掉了着色器里手写的 8 点插值）；
    离屏 FBO 渲染后读回位图，全程无 NDK——3D LUT 用不上 Vulkan 的设备/内存/同步管理，
    收益与风险不成比例
  - **直通路径**：新增 `LutProcessor.applyToBitmap`。字节数组接口要求调用方把 Bitmap 拆成
    IntArray 再转 RGBA 字节、处理完再拼回位图，1080p 图光这两趟 CPU 拷贝就要几十毫秒，
    足以吃掉 GPU 的收益；GPU 走「纹理上传 → 渲染 → 读回位图」直通，导出与预览都受益
  - **一致性**：调色换算抽成 `AdjustUniforms`（CPU 查表与 GPU uniform 同源），
    GPU 着色器与 CPU 实现的处理顺序/系数完全一致，回退时画面不突变（附一致性单测）
  - **回退与边界**：小图（滤镜缩略图级别，< 20 万像素）与字节数组接口走 CPU；
    大图按条带渲染（单条带 ≤ 400 万像素，避免一次申请两张全尺寸纹理）；
    图片/LUT 超出设备纹理上限只影响本次，EGL/着色器/GL 出错则记录原因并永久回退 CPU
  - GL 资源销毁回到 GL 线程执行（EGL 上下文线程绑定，跨线程删资源会静默失败并泄漏）

- **「LUT 调色」升级为「编辑调节」**（原 `LutEdit*` → `PhotoEdit*`，路由 `lut_edit` → `photo_edit`）：
  相册编辑中枢的入口改名为「编辑调节」，与下载页「编辑」按钮合并为同一个编辑器，
  原来的「基础调整」页（能力是其子集）随之移除，避免两个入口做同一件事
  - **新增裁剪**：比例预设（自由 / 1:1 / 4:3 / 3:2 / 16:9 / 9:16）+ 可拖动裁剪框
    （四角手柄缩放、框内拖动整体移动；锁定比例时以对角为锚点等比缩放），
    裁剪模式显示**未裁剪的底图 + 三分线覆盖层**，保证「框选的画面 = 导出的画面」
  - **新增旋转**：左/右 90°、水平/垂直翻转（裁剪框随画面同步变换，不会「漂」到别的内容上）、
    **拉直** -45°..45°（旋转后自动裁掉四角空白，适合校正地平线倾斜）
  - 几何换算集中在 `:image` 的 `Geometry`（纯函数 + 11 个单测）：旋转/翻转后的裁剪框位置、
    比例约束、拉直缩放比、拖动缩放换算；`ImagePipeline` 的几何顺序固定为
    **拉直 → 旋转 → 翻转 → 裁剪**
  - 界面改为「调色 / 裁剪 / 旋转」三个分区切换，不再把全部控件堆在一屏；
    导出文件名 `IMAGEDGE_LUT_*` → `IMAGEDGE_EDIT_*`

- **边框水印整体重做**（`ExifFrameViewModel` / `ExifFrameScreen`）：
  - **排版**：从「品牌 + 型号 + 全部参数挤一行、溢出就整体缩小后截断」改为**两行信息层级**
    （第一行品牌 LOGO + 型号，第二行参数以 `·` 分隔、次级灰），字号/颜色/基线各自独立，
    品牌 LOGO 与文字严格垂直居中
  - **字体**：统一用应用内自带的 Inter（Regular/Medium），不再用系统 DEFAULT / MONOSPACE——
    后者各机型字形不一、中文字距难看，是「效果差」的主因之一
  - **经典白边**：改为真正的**四边留白**（原实现是照片贴边 + 底部一条白栏，并没有白边）
  - **极简单行 → 极简叠字**：不再把半透明黑条画在照片下方（白底上就是一条灰带），
    改为在照片底部叠加**渐变遮罩**后叠字
  - 新增 **双行签名**模板（贴边 + 金色强调竖线 + 大写字距），模板总数 4 → 5
  - 新增**拍摄时间**字段、**自定义文字**（署名/地点/©）、品牌标识开关、照片圆角开关，
    每个字段可单独显示/隐藏
  - 导出修复：文件名拼写 `IMGDEGE` → `IMAGEDGE`、**保留原图 EXIF**（原先全丢，相册排序与
    拍摄信息都没了）、落盘补 `IS_PENDING` + `DATE_TAKEN`
- **LUT 调色从「只有强度滑条」升级为完整调色**（`LutEditViewModel` / `LutEditScreen` / `:lut`）：
  - 新增**曝光 / 对比度 / 饱和度 / 色温**四项基础调色，与 LUT 在**同一遍像素处理**内完成
    （曝光/对比度/色温折叠成 3×256 查表，饱和度单独一步；`ColorAdjust` + 6 组新单测）
  - **滤镜实时缩略图**：每个滤镜用**用户自己的照片**渲染预览（原先只有滤镜名，无法预判效果）
  - **长按对比原图**；新增「重置」一键回到原图
  - 修复**竖拍照片在编辑与导出后躺着**的问题（原实现不读 EXIF 方向）
  - 修复**导出分辨率腰斩**：原先用 1600px 编辑副本导出（6000px 照片只剩 1600px），
    现按可用内存上限重新按原分辨率解码，并**按条带处理**避免全图三缓冲 OOM
  - 导出**保留 EXIF** + `IS_PENDING`/`DATE_TAKEN`，文件名改为 `IMAGEDGE_LUT_*`
- **LIVE 图三拼修复**（`LiveTriptychViewModel` / `LiveTriptychScreen` / `VideoStitcher`）：
  - **修 bug：转码输入用错文件**——原先把**实况图本身（JPEG）**交给 Media3 Transformer，
    而不是解析出的 MP4；裁剪分数按视频尺寸算却作用在静态画面上，导出的动态部分要么是
    静帧要么直接失败
  - **修 bug：分段静音导致导出失败**——Media3 要求同一序列各段轨道数一致，原先对静音段
    用 `setRemoveAudio(true)` 会触发「前一段无音轨、后一段有音轨」错误。现在改为：只要有一段
    要声音就全部保留音轨、静音段用 `GainProcessor(0)` 压到 0 增益，并开启
    `experimentalSetForceAudioTrack()` 为「源本身无音轨」的段补静音轨
  - **修 bug：结果不可见**——成功后停留在预览页且不渲染 `message`，用户看不到「已保存」，
    失败也看不到原因。新增结果页（成功/失败都停留展示 + 「再拼一张」）
  - 导出失败不再删除槽位源文件（原先 `cleanup()` 把源也删了，用户被迫重新选三张图才能重试）
  - 画质：拼图抽帧宽度改为按格子宽（导出 1920/1080、预览 960），不再用 640px 抽帧
    再放大到 1920 的格子里（成品封面发虚的根因）；封面候选帧改用 `OPTION_CLOSEST`，
    点选的高亮帧与实际使用的帧一致
  - 内存：实况图静态帧改为采样解码（原先为一张 360px 缩略图整图解码 24MP）
  - 重置封面时同步作废预览（原先预览停留在旧画面上）

### Changed / 变更（文档）

- README（中英）：版本徽章对齐 `0.2.0-alpha03`、Kotlin 徽章对齐 2.3.21、
  `compileSdk 36 → 37`、模块表补 `:share` 与 `:image`、功能段补导出分享与非破坏性调整、
  更正「分块断点续传」的过时表述（0.1.8 起已回归整文件下载）
- `docs/HANDOFF.md`：更正「`:image` 全项目零引用」的错误结论（实际已被基础调整使用），
  更新待办优先级，新增 4 条已知坑（配网凭据分支、MediaStore 落盘、dataSync 时限、PtpIoException 语义）

## [0.2.0-alpha03] - 2026-09-05

> **Alpha 测试版**。液态玻璃视觉全面铺开 + 交互细节打磨。

### Added / 新增（液态玻璃全覆盖）

- **悬浮导航栏真正悬浮**：从 Scaffold 移出改为 Box 叠加，内容可滚动穿过导航栏并被其折射；双背景源（页面背景层供卡片 / NavHost 内容层供导航栏），保持无渲染递归
- **玻璃背景光晕**：页面背景铺低饱和多色径向光斑（青蓝/紫/暖橙/青绿），玻璃终于有内容可折射；明暗主题各自适配
- **玻璃质感强化**：更低模糊 + 更强边缘折射 + depthEffect + 色差（RGB 分离）+ vibrancy；沿形状的 iOS 式 hairline 描边
- **GlassCard / GlassSwitch / GlassDialog**：替换裸 Material3 容器为玻璃实现
- **所有按钮统一走 AppButton**（含自定义内容槽）：主页大按钮、设置「导入 .cube」等一次修改全部生效
- **二级/三级页返回钮玻璃化**（PageHeader 统一改动）

### Changed / 变更

- HUB 图标选中**不再上浮**（指示条 + 主色已表达选中）
- 权限使用说明页标题**不再悬浮**，随内容滚动
- 相册页、编辑中枢、视频转 Live 补齐滚动——所有页面支持滑动（遥控实时取景页除外）

### Fixed / 修复

- 页面内玻璃元素引发 RenderThread 栈溢出的递归问题（背景层/内容层分离解决）
- AppButton 玻璃路径在部分设备渲染为黑色实心块（弃用 M3 Button + drawBackdrop，改透明 + 描边实现）

## [0.2.0-alpha02] - 2026-09-05

> **Alpha 测试版**。工具链整体升级 + 液态玻璃视觉。

### Changed / 变更（工具链升级）

- **AGP 8.11.2 → 9.4.0**（连带 Gradle 8.13 → 9.7.1）：AGP 9 内置 Kotlin 支持，各模块已移除 `kotlin-android` 插件
- **Kotlin 2.2.0 → 2.3.21**（KSP 2.2.0-2.0.2 → 2.3.11）：Hilt 2.59+ 的元数据格式要求
- **Hilt 2.56.2 → 2.60.1**：2.59+ 起使用 AGP 9 的 ScopedArtifact API
- **Compose BOM 2026.04.01 → 2026.08.00**、**Room 2.7.2 → 2.8.4**、**compileSdk 36 → 37**（全部模块）

### Added / 新增（液态玻璃）

- **`io.github.kyant0:backdrop 2.0.1`**（Apache-2.0）：iOS 26 风格的液态玻璃效果（背景模糊 + 边缘折射 + vibrancy）
- 底部悬浮导航改为玻璃胶囊（背后是页面内容，真实折射）
- 动态降级：API 33+ 完整玻璃 / 31–32 仅模糊 / 更低或省电模式、低内存设备退回普通表面（观感与改造前一致）

### Fixed / 修复

- **真机崩溃（Redmi/Android 16 实测）**：页面内元素（EntryCard）的玻璃效果会引发 RenderThread 栈溢出（SIGSEGV）——`drawBackdrop` 引用了 `layerBackdrop` 采集范围内的祖先图层，形成渲染递归。已回退页面内玻璃，仅保留导航栏（位于 `bottomBar`，不在采集范围内，架构安全）；页面内玻璃待「背景层/内容层」分层重构后再启用

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
