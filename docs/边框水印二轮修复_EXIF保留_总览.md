# 边框水印二轮反馈修复 + EXIF 保留 — 工作总览

> 2026-08-31 · 编译通过 · APK 已安装测试机 7P7XOBJBTOSOSWUC · logcat 已清空待复测

## 本轮完成内容

### 1. 11MB 大图「不能完整显示」— 实证排查 + 堵住盲区

**实证过程**（非猜测）：
- 真机 logcat（19:52 用户测试）：`picked` → `EXIF 结果` 全程**无任何解码失败日志** → `decodeScaled` 三条路径至少一条成功
- 用 `adb pull` 拉取问题照片 **DSC0432.jpg**（11.1MB，4000×5328 竖拍，ZV-E10）→ Python 逐段解析：**结构完全正常的 baseline JPEG**（SOF=0xC0，非渐进式，无嵌入视频段，EXIF/ICC/XMP 段完整）
- 无 OOM / FATAL / crash

**结论**：解码层与文件本身均无异常，问题聚焦在渲染/状态层，修复两处盲区：
| 问题 | 修复 |
|---|---|
| `renderPreview` 的 `runCatching{renderFrame}.getOrNull()` 吞掉异常，且失败时 `preview` 被清成 null → 界面空白且无提示 | 渲染失败**打日志** + **保留旧预览**（不再清空）+ 给用户提示 |
| 流路径 `streamOpened = decodeStream(...) != null`：`inJustDecodeBounds=true` 时 `decodeStream` **恒返回 null** → 流路径兜底从未真正执行 | 改为 `use` 块内显式置 `streamOpened = true` |
| fd 解码成功无日志，真机难以核对 | 解码成功打尺寸日志：`fd 路径解码 WxH (target=N)` |

### 2. 白框悬浮观感（真机反馈）
- **阴影加强**：偏移 x 0.02w / y 0.03h，BlurMaskFilter 模糊 0.03w，alpha 110 → 150（原阴影太淡不可见）
- **底部信息栏调低**：0.24w → 0.16w（min 72 → 56）
- 顶部白边 0.09 → 0.07；logoH/字号比例微调（0.50 / 0.30 × bottomH）

### 3. 品牌 LOGO 原始文件包
- 打包：`docs/品牌logo原始包.zip`（16 个 VectorDrawable + 9 个 PNG + 修改说明.md）
- 修改说明含：视觉大小一致（PNG 裁边 / vector 视口撑满）、垂直居中（透明留白裁剪、`translateY` 微调）

### 4. EXIF 保留（新需求：视频转 LIVE / 三拼 LIVE 导出保留原素材 EXIF）
- 新增 `MotionPhotoExifPreserver`（motionphoto 模块）：
  - **图片源**：MAKE / MODEL / 拍摄时间 / 曝光 / ISO / 焦距 / 镜头 / Flash 全量注入
  - **视频源**：ExifInterface 不支持 MP4，退化为拍摄时间（`METADATA_KEY_DATE` → MediaStore `DATE_TAKEN` 兜底）
  - 封面方向恒置 1
- `MotionPhotoComposer.compose` 新增可选参 `exifSourceUri`，透传至 Engine → StillImagePreparer（落盘后、MP 打包前注入，不破坏 MP 结构）
- **三拼**：`exifSourceUri = slots.first().sourceUri`（顶部格 = 第一个 LIVE 图）
- **视频转 LIVE**：`exifSourceUri = clip.uri`（源视频）
- **补充加固 ①**：`MotionPhotoGalleryWriter` 的 `DATE_TAKEN` 从封面 EXIF `DATETIME_ORIGINAL` 读取（无则退回导出时间）——成品在相册时间线 = 源素材拍摄时间，不再全部显示为导出当天
- **补充加固 ②**：`ExifFrameViewModel` 画框实况导出也传 `exifSourceUri = sourceUri`——画框成品同样保留原图拍摄信息

## 改动文件
- `motionphoto/.../internal/compose/MotionPhotoExifPreserver.kt`（新增）
- `motionphoto/.../internal/compose/MotionPhotoStillImagePreparer.kt`（加 `exifSourceUri`）
- `motionphoto/.../internal/compose/MotionPhotoComposeEngine.kt`（透传）
- `motionphoto/.../MotionPhotoComposer.kt`（facade 加参）
- `app/.../edit/VideoToLivePhotoViewModel.kt`（`exifSourceUri = clip.uri`）
- `app/.../edit/LiveTriptychViewModel.kt`（`exifSourceUri = slots.first().sourceUri`）
- `app/.../edit/ExifFrameViewModel.kt`（渲染失败日志/防御、流路径 bug、阴影加强、信息栏调低、解码尺寸日志、画框导出 EXIF 透传）
- `motionphoto/.../internal/io/MotionPhotoGalleryWriter.kt`（`DATE_TAKEN` 从封面 EXIF 读取）

## 待真机确认
1. 11MB 大图导入预览完整显示（抓 `exifframe` tag：`fd 路径解码 WxH` / `渲染失败`）
2. 白框悬浮：投影可见、信息栏高度合适
3. 三拼 LIVE 导出 → 相册「详细信息」显示第一张 LIVE 图的机型/参数/时间
4. 视频转 LIVE 导出 → 相册时间线为源视频拍摄时间
