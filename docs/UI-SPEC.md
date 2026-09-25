# Imagedge UI 设计规范

> 版本 1.0 · 2026-09-11 · 适用于 `app/` 下全部界面
>
> **这份文档怎么用**
> 1. 动手改界面前，先读第 2 节（原则）与第 4 节（Token）——实现只允许引用 token，不允许写字面量；
> 2. 组件选择查第 6 节的决策表，不要现场发挥；
> 3. 提交前逐条过第 9 节清单；
> 4. 规范与代码冲突时，改代码或**先改规范并说明理由**，不接受「这次先例外」。

---

## 1. 我们的界面分三类，规则不同

| 类型 | 例子 | 核心诉求 | 布局要点 |
|---|---|---|---|
| **工具型** | 相册、编辑调节、下载队列、设置 | 操作密集、状态可逆、反馈即时 | 统一页面骨架 + 分组区块；主操作固定在内容末端 |
| **状态型** | 连接、遥控拍摄 | 状态优先、单一主操作、实时反馈 | 主操作放在屏幕下 1/3（拇指可达）；状态胶囊常驻 |
| **沉浸型** | 大图查看、实时取景、裁剪 | 内容最大、控件最少 | 黑底/满屏；控件半透明并可淡出；退出路径明确 |

> 判断标准：**照片/取景画面是不是这一屏的主角？** 是 → 沉浸型；否 → 工具型；
> 屏上有「连接中/已连接」这类实时状态 → 状态型。

---

## 2. 五条原则

1. **内容优先（Content first）**
   来自 Apple HIG 的 *deference*：界面不抢内容。黑白色板与液态玻璃只是容器，
   照片永远是画面里对比最强、最亮的东西。→ 玻璃强度设上限；文字不压在照片主体上；
   看图页背景必须是纯黑（`ViewerBackdrop`）。

2. **一屏一个主操作**
   每个页面只有一个 `AppButton(PRIMARY)`，其余动作降级为 SECONDARY / GHOST。
   同一屏出现两个实心主按钮视为规范违规。

3. **状态必须可见**
   任何异步操作都有 **加载 → 成功/失败** 三态；失败必须给「原因 + 下一步」，
   不能只显示「失败」。参考 M3 的 state layer 思路：状态变化用**层级与图标**表达，
   而不是只换颜色（色弱用户同样要能分辨）。

4. **结构可预测**
   同一层级的页面用同一骨架：`PageHeader → 内容（统一 16dp 边距、12/16 节奏）→ 主操作`。
   用户在 A 页学到的结构，在 B 页必须成立。

5. **默认可达（Accessible by default）**
   对比度 ≥ 4.5:1、触控目标 ≥ 48dp、语义不单独依赖颜色、系统字体缩放生效。
   这是**默认要求**，不是「后续优化」。

---

## 3. 对标学习：我们取什么、不取什么

### 3.1 规范层

| 来源 | 取 | 不取 |
|---|---|---|
| **Material 3**（含 Expressive） | 语义色角色（`primary` / `on*` / `*Container`）、8dp 栅格与间距刻度、字阶的「大标题 + 小标签」对比、组件状态层、预测性返回 | 默认动态取色（紫）、默认 elevation 阴影——与极简黑白 + 玻璃的层级策略冲突 |
| **Apple HIG** | 44×44pt 最小触控（我们取 48dp）、Safe Area、Materials 的分层与深度、Clarity（用字重与留白建立层级）、触觉与动作一一对应、破坏性操作红色 + 二次确认 | 全屏毛玻璃滥用——我们已用能力分级限制（API<31 或省电/低内存直接退回普通表面） |
| **WCAG 2.2** | 1.4.3 正文对比 4.5:1、1.4.11 非文本对比 3:1、2.5.8 目标尺寸下限、1.4.4 缩放文本 | — |
| **Android 平台** | 边到边（`enableEdgeToEdge` + insets）、系统字体缩放、TalkBack 语义与焦点顺序、通知渠道/前台服务可见性 | — |

### 3.2 顶尖作品

| 作品 | 学什么 | 在本项目的落地 |
|---|---|---|
| **Sony Creators' App** | 连接是「多步引导」而不是一个按钮；后台传输要有可见性与断点续传 | 首页三步引导卡（已有）、传输通知可点击（已有）；补「连接失败原因 + 重试」入口 |
| **DJI Mimo** | 创作流线性推进（选素材 → 编辑 → 导出在同一流程内前后推进）；模板化预览所见即所得 | 编辑调节三分区 + 实时预览（已有）；规范要求流程页统一「上一步/下一步」 |
| **Lightroom Mobile** | 工具条按「调整 → 颜色 → 效果 → 裁剪」分组；长按对比原图；每个参数可重置 | 编辑调节已实现长按对比与重置；规范把「参数组 + 重置 + 对比」定为标准配置 |
| **Google Photos** | 空态/加载/错误三态的一致表达；网格密度；可撤销的删除 | 统一用 `States.kt` 三态组件；Snackbar 提供「撤销」（待排期） |
| **Apple Photos** | 沉浸看图（黑底、控件淡出）；编辑永远非破坏性、可随时复原 | 看图页黑底（已有）；编辑页「重置」+ 非破坏性 `EditStep`（已有） |
| **Halide / ProCamera** | 相机 UI 的拇指可达布局、超大快门键、实时参数条 | 遥控拍摄页参数条 + 快门（已有）；规范限定状态型页面主操作落在下 1/3 |
| **Snapseed** | 手势即参数（上下滑动选参数、左右滑动调值） | 列为 P2 手势增强，不进本轮规范 |

---

## 4. Token（唯一来源）

所有取值必须来自 `ui/theme/`。**`feature/` 下不允许出现**颜色字面量、非 token 字号、
非 token 圆角与非 token 间距。

### 4.1 颜色（`theme/Color.kt`）

- **主色只有黑白两态**：浅色 `InkLight #1A1B1E` / 深色 `InkDark #E8E9EB`，无彩色主色；
- 语义色（成功 / 警告 / 错误 / 信息）**只表状态**，且必须「图标 + 文字」双保险；
- 纯黑仅允许用于沉浸型背景（`ViewerBackdrop`）；
- 正文与次要文字对比度均需 ≥ 4.5:1（现有 `onSurfaceVariant` 已满足；新增色必须实测）。

### 4.2 字阶（`theme/Type.kt`，7 级）

| 角色 | 字号 | 字重 | 行高 | 用途 |
|---|---|---|---|---|
| `displayLarge` | 48 | Medium | 56 | 首屏品牌字（仅首页/空态大标题） |
| `headlineLarge` | 36 | Bold | 44 | 页面主标题 |
| `headlineMedium` | 30 | Bold | 38 | 区块大标题 |
| `headlineSmall` / `titleLarge` | 24 | Bold | 32 | `PageHeader` 标题、卡片组标题 |
| `titleMedium` / `titleSmall` | 16 | Medium | 24 | 卡片/列表项标题、行内强调 |
| `bodyLarge` / `bodyMedium` / `bodySmall` | 16 / 14 / 14 | Regular | 24 / 22 / 20 | 正文与说明 |
| `labelLarge` / `labelMedium` / `labelSmall` | 14 / 12 / 12 | Medium | 20 / 16 / 16 | 按钮、标签、角标 |

规则：**一屏最多 3 个字号层级**；层级靠「字号 + 字重」双重区分，不靠单纯放大。

### 4.3 圆角（`theme/Shape.kt`，6 档）

`Tag 8`（标签/角标）· `Control 12`（按钮/输入框/下拉）· `Card 16`（卡片/列表项）·
`Container 20`（大容器/分组）· `Sheet 28`（半屏弹窗顶部）· `Pill 50%`（胶囊/头像）。

### 4.4 间距（`theme/Spacing.kt`，6 档）与页面节奏

`XS 4` · `S 8` · `M 12` · `L 16` · `XL 24` · `XXL 32`

| 场景 | 取值 |
|---|---|
| **页面左右边距** | **`L 16`（全项目统一）** |
| 页面底部留白 | 内容末 + `navigationBars` inset + `XL 24` |
| 区块（Section）之间 | `L 16` |
| 区块标题与内容之间 | `M 12` |
| 同组元素之间 | `S 8` |
| 标签与控件之间 | `XS 4` |
| 卡片内边距 | `M 12` ~ `L 16` |
| 列表行最小高度 | `56`（含 48dp 触控目标） |

### 4.5 动效（`theme/Motion.kt`）

- `springSnappy`：小元素（指示条、图标、按压回弹）
- `springSoft`：容器（导航滑条、面板弹入）
- `durationShort 150`：状态切换；`durationStandard 250`：页面/面板进出
- 禁止为动效而动效：**只有「状态变化」或「空间关系变化」才加动画**（M3 motion 原则）

### 4.6 层级：不用阴影

层级只靠三档表面色（`background` / `surface` / `surfaceVariant`）+ hairline 描边表达。
唯一例外是悬浮导航（`navigation/FloatingNavBar`，用极淡的自定义阴影表达「浮在内容之上」）。

---

## 5. 布局规范

1. **页面骨架**统一走 `AppScreenFrame`：它是**安全区域的唯一所有者**——top 只给标题栏，
   bottom + horizontal 只给内容；标题用 `AppPageHeader`（只设最小高度，不自己吃系统边距）。
   旧的 `AppPage` / `PageHeader` 保留给尚未迁移的页面，**同一页只能有一套**：
   外层再补一次 `statusBars` 就是所有二级页顶部多出一条空隙（批次 A 收口的就是这个）。
2. **所有非沉浸页必须可滚动**。安全区内边距由 `AppScreenFrame` **自己加完**，不再往页面传
   `PaddingValues`：传参写法实测翻车过——页面把 `padding(innerPadding)` 写在 `verticalScroll()`
   **之后**，内边距就成了滚动内容的一部分，往下滚时正文直接压在大字标题上；
   顺序写错编译器不管、单测不管，所以不给页面写错的机会。
   Tab 页（没有标题栏）自己吃 `statusBarsPadding()`，底部为悬浮导航让位统一读
   `LocalNavClearance.current`（胶囊实高下发；写死数值在大字模式下会被遮挡）。
3. **主操作位置**：工具型放在滚动内容末端；状态型固定在下 1/3 区域；
   **也允许固定在底部操作区**（如选择态的 `SelectionActionBar`）——前提是同一屏不同时叠两层底部条。
4. **网格**：相册 3 列（窄屏 2 列）间距 4；列表单列，左侧 40dp 图标槽对齐。
5. **宽屏**：表单与长说明最大 600dp 居中；遥控/编辑这类画面型页面用画面＋工具双栏，不锁 600dp。
6. **沉浸页**：黑底、控件半透明、提供明确的退出路径（返回钮 48dp 触控目标）。
7. **结构尺寸**取自 `theme/UiSize.kt`（触控 48 / 标题栏 56 / 行 64 / 导航 64 / 照片格 104 /
   表单上限 600 / 快门 72）。它们是**下限与上限，不是固定高度**：大字模式随内容增高。

---

## 6. 组件规范

### 6.1 决策表（唯一入口）

| 需求 | 用 | 不要用 |
|---|---|---|
| 主/次/文字动作 | `AppButton(PRIMARY / SECONDARY / GHOST)` | 裸 `Button` / `TextButton` |
| 行内文字动作（表格、卡片尾部） | `AppLink` | 裸 `TextButton` |
| 开/关 | `AppSwitchRow`（内部 `GlassSwitch`） | 裸 `Switch` |
| 互斥选项 ≤ 4 个 | `AppChipRow` + `AppChip` | 裸 `FilterChip` / `AssistChip` |
| 互斥选项 > 4 个或比例类 | `AppChipRow(scrollable = true)` | 横向自绘 |
| 数值参数 | `AppSlider`（标签 + 滑条 + 数值） | 裸 `Slider` |
| 文本输入 | `AppTextField` | 裸 `OutlinedTextField` |
| 强调容器（一屏最多一处） | `GlassCard` | 裸 `Card` |
| 普通入口行 / 设置行 | `ActionRow` / `SettingsRow`（不透明表面） | 给每个入口套玻璃 |
| 底部传输状态条 | `feature/transfer/TransferMiniBar`，由 `AppRoot` 按 `bottomLayoutOf` 统一调度 | 页面自己 `align(Bottom)` 贴一条，与导航/保存条互相盖 |
| 一级页的任务入口 | `ActionRow`（同级同表面，禁用必须带原因）；页面私有组件（如 `CameraStatusCard`）留在 `feature/<页>/` | 为设计文档里的名字（`TaskEntry`）再建一个同义包装 |
| 分组标题 | `GroupTitle` | 手拼 Row + Text |
| 引导卡 / 情境提示 / 步骤面板 | `ui/guidance/`：`GuideCard` / `ContextHint` / `HelpSheet` | 页内自拼说明块 |
| 区块标题 + 内容 | `AppSection` | 手拼 Column |
| 非沉浸页骨架 | `AppScreenFrame` + `AppPageHeader`（内边距由骨架加完，页面拿不到 `PaddingValues`） | 页面自拼 `Scaffold`、自己补 `statusBarsPadding` |
| 切 Tab / 进子页 | `navigation/` 的 `selectTab` / `openSubDestination` | 页面里写死路由字符串调 `navigate()` |
| Tab 页底部让位 | `LocalNavClearance.current`（胶囊实高下发） | 写死 96dp 之类的常量 |
| 空/加载/结果/横幅 | `States.kt`（`EmptyState` / `ProcessingView` / `ResultMessage` / `StatusBanner`） | 临时 Text |

**例外**：沉浸型页面（取景、看图、裁剪覆盖层）允许使用平台绘制原语（`Canvas`、
`pointerInput`），但按钮与状态仍走设计系统组件。

**普通不透明入口容器是本规范允许的一等公民**（`ActionRow` / `SettingsRow` / `GuideCard`）。
玻璃只用于少量强调容器：一屏四五个玻璃块互相抢注意力，等于没有重点。
新增此类容器请复用上面两个组件，**不要**通过扩大 `uiSpecCheck` 的豁免清单来绕开组件规则。

### 6.2 玻璃使用边界

- 只用于**容器层**：导航、卡片、弹窗、按钮；不允许在 M3 容器上再叠玻璃
  （已知坑：会渲染成黑色实心块）；
- 玻璃参数集中在 `ui/glass/GlassSurface.kt` 的 `GlassSpec`；
- 新增可点击玻璃组件用 `Modifier.glassReactive(onClick)`，不要直接 `clickable`（缺按压反馈）。

---

## 7. 状态规范

| 状态 | 表达 | 必备元素 |
|---|---|---|
| 空 | `EmptyState` | 图标 + 标题 + 说明 + 一个主操作 |
| 加载（页面级） | `ProcessingView` | 菊花 + 说明文字（说清「在做什么」，不是「请稍候」） |
| 加载（按钮级） | 按钮内联菊花 + 按钮禁用 | 文案变为进行时（「正在导出…」） |
| 成功 | `ResultMessage(ok = true)` + 轻提示 | 明确结果与位置（「已保存到相册」） |
| 失败 | `ResultMessage(ok = false)` | **原因 + 下一步**（「相机连接已断开，请重新连接后再下载」） |
| 断开/警告（常驻） | `StatusBanner` | 文案 + 可选修复动作 |

**防重**：任何会产生副作用的操作，进行中必须禁用入口（按钮 `enabled = false`）。

---

## 8. 无障碍

1. **触控目标 ≥ 48dp**：视觉可以更小，但触摸区必须够（`PageHeader` 返回钮、图标按钮、裁剪手柄）。
2. **图标按钮必须有 `contentDescription`**；纯装饰图标显式 `null`（避免重复播报）。
3. **不使用颜色单独承载信息**：状态 = 图标 + 文字 + 颜色。
4. **动态字体**：不写死高度，行高与容器随字体缩放；`DesignScaleLocked` 已保留 `fontScale`。
5. **焦点顺序**与视觉顺序一致；弹窗打开时焦点进入弹窗、关闭后回到触发点。

---

## 9. 提交前清单

- [ ] 颜色/字号/圆角/间距全部来自 token，`feature/` 无字面量
- [ ] 页面左右边距 = 16dp，骨架用 `AppScreenFrame`（未迁移页沿用 `AppPage`），非沉浸页可滚动
- [ ] 安全区域只被消费一次：根布局不额外加 `statusBars`，标题栏不自己再吃一遍
- [ ] 一屏只有一个 PRIMARY 主操作；主操作在内容末端、下 1/3，或独占一个底部操作区（三者取一）
- [ ] 组件来自第 6 节决策表，没有裸 M3 控件（除例外清单）
- [ ] 异步操作有加载/成功/失败三态，失败信息含原因与下一步
- [ ] 触控目标 ≥ 48dp；图标按钮有 contentDescription
- [ ] 深色与浅色主题都过一遍（语义色对比度、玻璃降级路径）；**深色下正文要真量一次**
      （批次 A 的黑字缺陷肉眼在浅色下完全正常，截图取像素才发现）
- [ ] 玻璃参数未新增散值（统一在 `GlassSpec`）
- [ ] 路由字符串只出现在 `navigation/` 内，页面只收回调；同一页面只有一条进入路径
- [ ] 真机过一遍：字体缩放 200%、省电模式（玻璃降级）、横屏/窄屏
      （1.3× 挡不住大字遮挡，批次 B 的两个缺陷都在 200% 才现形）

---

## 10. 附录：规范化进度与防回退机制

### 10.1 已落地

| 项 | 结果 |
|---|---|
| 设计系统 token | 5 组（颜色/字阶/圆角/间距/动效）在本规范定稿，作为唯一来源 |
| 基础组件 | `AppPage`（页面骨架）、`AppSection`（区块）、`AppChip` / `AppChipRow`（选项）、`AppSlider`（参数）、`AppTextField`（输入）、`AppSwitch` / `AppSwitchRow`（开关）、`AppIconButton`（图标按钮）、`AppLink`（行内动作）、`AppDivider` |
| `PageHeader` 返回钮 | 36dp 触控 → **48dp 触控**（视觉仍 36dp） |
| 页面迁移 | **`feature/` 下裸 M3 控件全部清零**（46 → 0）：编辑调节、边框水印、LIVE 三拼、编辑中枢、下载页、设置页、首页、遥控页、视频转 Live、导出设置面板、相册页、看图页、扫码页 |
| 页面骨架 | 5 个工具页统一走 `AppPage`（边距 16dp + 滚动 + 导航栏避让）；批次 A 新增 `AppScreenFrame`（设置页已迁），批次 B 把悬浮导航的底部让位量解决掉（`LocalNavClearance` 实测下发），批次 C 传输页迁到 `AppScreenFrame` + `AppPageHeader`（记录行/任务行的动作单独占一行，200% 字体下不裁字）；其余 Tab 页与沉浸页的骨架迁移仍单独排期 |
| 颜色字面量 | `feature/` 下为零（沉浸层遮罩/手柄统一用 `ViewerBackdrop` / `OnViewer`） |
| 图标资产 | 新增 `ArrowUp` / `ArrowDown`（三拼重排序需要），补齐 `Lucide` 集合 |

### 10.2 防回退机制（已启用）

**`./gradlew :app:uiSpecCheck`** —— Gradle 校验任务，已并入 `check` 与 CI
（`.github/workflows/ci.yml` 的 “UI spec check” 步骤，位于 lint 之前）：

- 扫描 `feature/**/*.kt`（跳过注释与文档注释、保留 URL 中的 `//`）；
- 命中 `Button(` / `TextButton(` / `IconButton(` / `FilterChip(` / `AssistChip(` /
  `OutlinedTextField(` / `Switch(` / `Slider(` / `Card(` 即失败，并逐条给出
  **文件:行号 + 该改用的组件**；
- 用词边界排除设计系统组件自身（`AppButton(` / `GlassCard(` / `AppSlider(` …），
  因此 `ui/` 目录无需豁免；
- 已验证：注入一处 `TextButton(` 会立刻以 `settings/SettingsScreen.kt:442 直接使用了 M3 的
  TextButton —— 请改用 AppLink` 失败；撤销后恢复通过。

### 10.3 仍需人工守的部分

1. `AppPage` 的 `contentPadding` 默认值即规范值，页面不应覆盖（沉浸页除外）；
2. 三个 Tab 页（相机 / 照片 / 创作）仍是裸 `Column + statusBarsPadding`，尚未迁到
   `AppScreenFrame`；底部让位量已由 `LocalNavClearance` 实测下发，迁移时直接读它即可；
3. 新增页面请在 PR 描述里附「浅色 + 深色 + 字体 200%」三张截图——批次 B 的两个缺陷
   （深色主题黑字、写死的导航让位量在大字下遮挡最后一行）都只在深色或 200% 下才出现，
   1.3× 挡不住；
4. 语义色对比度目前只有人工核对，后续可接入 `Accessibility Scanner` 或截图对比工具。
