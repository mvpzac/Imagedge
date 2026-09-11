# Third-Party Notices 第三方组件声明

Imagedge 本体代码以 [Apache-2.0](LICENSE) 发布。下列第三方组件、代码移植与协议参考资料
各自遵循其原始许可证；分发本项目的二进制或源码时请一并保留本文件。

> 本文件只声明**确实进入本仓库的成果**（代码移植、资产、协议参考）。依赖库（AndroidX、
> Kotlin、Media3、Coil、zxing、backdrop 等）的许可证由 Gradle 依赖树携带，可在
> Android Studio 的 `Gradle > Dependencies` 或 `./gradlew :app:dependencies` 中查证。

## 1. 代码移植（含上游版权声明）

### MotionPhotoLab — `:motionphoto` 模块

- 来源：https://github.com/SuoxingTech/MotionPhotoLab
- 用途：动态照片（Motion Photo / LIVE Photo）封装、解析与厂商 XMP 对齐的实现基础；
  本项目的 `motionphoto` 模块在其基础上移植并适配（Media3 `MuxerUtil` 路线）。
- 许可证：MIT

```
MIT License

Copyright (c) 2026 Suoxing Tech

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 2. 协议参考（不含代码复制）

下列项目**仅作为协议行为的事实来源被参考**（操作码、初始化序列、BLE 命令码、二维码字段
语义、LiveView 帧封装变量等）。协议常量与硬件行为事实不构成受版权保护的表达，本项目
的实现为独立编写；如后续引入其中任何**代码**，必须先完成许可证兼容性评估。

| 项目 | 许可证 | 参考内容 |
|---|---|---|
| [frank26080115/alpha-fairy](https://github.com/frank26080115/alpha-fairy) | MIT | Sony PTP/IP 双连接握手顺序与 SDIO 初始化序列表（`init_table`） |
| [Staacks/alpharemote](https://github.com/Staacks/alpharemote) | **GPL-3.0** | 索尼「蓝牙遥控」BLE 命令码表（0x06–0x15）与状态特征（ff02）语义。**注意：本项目未复制其代码**，仅使用协议常量；GPL-3.0 与 Apache-2.0 不兼容，禁止直接移植其实现 |
| [gkoh/furble](https://github.com/gkoh/furble) | MIT | 同上，作为 BLE 码表的第二处独立印证 |
| [gphoto/libgphoto2](https://github.com/gphoto/libgphoto2) | LGPL-2.1 | PTP 设备能力协商与机型差异的对照参考 |
| [Fimagena/libptp](https://github.com/Fimagena/libptp) | LGPL-2.1 | ISO 15740 字节布局对照（`:ptp` 为纯 Kotlin 独立实现，规避 LGPL 派生义务） |
| [tomo0611/Sony-ZV-E10-RX-Android](https://github.com/tomo0611/Sony-ZV-E10-RX-Android) | 未声明 | ZV-E10 同机型的私有操作码（0x9210 / 0x9212 / 0xF10001 虚拟存储）行为对照 |

## 3. 资产（Assets）

| 资产 | 位置 | 来源与授权 |
|---|---|---|
| 创意风格 LUT（8 个） | `app/src/main/assets/luts/*.cube` | 由 [EditClips](https://editclips.online) 生成，免费使用；文件头保留来源注释 |
| S-Log2 / S-Log3 → Rec.709 转换 LUT（2 个） | `app/src/main/assets/luts/SLog*_to_Rec709.cube` | 项目自制（依据索尼公开的传递函数，用 colour-science 计算），随本体以 Apache-2.0 发布 |
| 品牌 LOGO（EXIF 边框用） | `app/src/main/assets/brand_logos/*.png` | 各品牌商标，仅用于标识拍摄设备（指明性使用）；本项目与各品牌无关联、未获其认可 |
| 应用图标与设计源 | `design/ic_launcher/` | 项目自制（Apache-2.0） |

## 4. 提交贡献时的注意事项

- 从其他开源项目移植代码前，先确认许可证兼容：本项目的 Apache-2.0 **不能**直接吸收
  GPL-3.0（alpharemote 等）代码；LGPL 代码需按动态链接与声明要求处理。
- 新增移植代码请在对应文件头部注明上游项目、版权归属与许可证，并同步更新本文件。
- 仅参考协议行为（码表、序列、字段语义）时，请在注释里写明"协议事实参考自 X，实现独立编写"。
