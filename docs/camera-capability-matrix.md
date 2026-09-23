# Camera Capability Matrix 相机能力矩阵

能力模型的判定规则、已确认事实与未验证项。配套代码：`data/model/CameraIdentity.kt`、
`data/model/CameraCapabilities.kt`、`data/remote/CameraRepository.kt`。

**本文档区分三类陈述**：`实测` = 真机往返验证过；`协议` = 由 PTP/索尼描述符结构推出；
`未验证` = 尚无任何证据，不得当成可用功能。当前仓库基线的真机经验只覆盖
**ZV-E10 + 固件 2.03**（见 `sony-protocol-notes.md`）。

## 1. 判定规则

每项能力有四个状态，由 `CameraCapabilities.fromDescriptors()` 判定：

| 状态 | 触发条件 | 界面表现 | 是否下发命令 |
|---|---|---|---|
| `UNKNOWN` | 尚未探测，或 0x9209 读取失败（超时/断线） | 禁用 + 说明「未知，非不支持」 | 否 |
| `UNSUPPORTED` | 0x9209 成功返回但缺该属性描述符；或传输通道结构性不具备 | 禁用 + 说明不支持 | 否 |
| `READ_ONLY` | `GetSet≠0x01`，或 `IsEnabled=0`，或值宽度不在 2/4 字节 | 只读文本 + 原因 | 否 |
| `WRITABLE` | `GetSet=0x01` 且 `IsEnabled=1` 且值宽度可写 | 下拉选择器 | 是 |

三条硬规则：

1. **请求超时绝不记成「不支持」**。`props == null`（读取失败）一律落到 `UNKNOWN` 且
   `stale = true`；下一次成功读取即恢复真实状态，失败不是永久性的。
2. **陈旧快照不得下发命令**。`canWrite()` 在 `stale` 时返回 false（通道自我声明的
   遥控拍摄能力除外——它来自当前活跃通道而非描述符缓存）。
3. **选项只能来自相机上报的描述符**。`WRITABLE` 但既无枚举表（FormFlag=0x02）也无
   取值范围（FormFlag=0x01）时，`optionsFor()` 返回空表、界面禁用。没有硬编码档位表兜底。

判定依据记录在 `CapabilityDetail.evidence` 与 `.note` 里，并在 `refreshCapabilities()`
时整份写入日志（tag `camera`）——这是排查「为什么这项被禁用」的唯一线索，也是采集新机型
能力矩阵的原始材料。

## 2. 能力归档键

快照按 `CameraIdentity(model, firmware, transport, mode)` 归档，`snapshotKey` 即四元组拼接。
连接成功、断开、功能模式切换三者都会重建快照：**换模式/换通道后沿用旧能力，正是把某台相机
某一次的经验泛化成「这个型号支持 X」的来源**。

换镜头不改变四元组，靠 0xC203/0x4006 属性变化事件触发重读（`CameraControlViewModel.init`
中 300ms 去抖）。`未验证`：换镜头是否一定推送该事件，尚无真机记录。

## 3. 属性码登记表

只登记**已确认**的属性码。宽度与取值形式一律以相机当次上报为准，表中「上报宽度」仅为
ZV-E10 的历史观察，不参与判定。

| 能力 | 属性码 | 值语义 | ZV-E10 上报宽度 | 证据 |
|---|---|---|---|---|
| ISO | `0xD21E` | 低 24 位 = ISO 值，`0x00FFFFFF` = Auto | UINT32 (4) | 实测 |
| 光圈 | `0x5007` | f 值 ×100 | UINT16 (2) | 实测 |
| 快门 | `0xD20D` | 高 16 分子 / 低 16 分母，0 = BULB | UINT32 (4) | 实测 |
| 照相模式 | `0x500E` | ExposureProgramMode 枚举 | UINT32 (4) | 实测 |
| 白平衡 | `0x5005` | 枚举（见 `formatWhiteBalance` 值表） | — | `协议` |
| 曝光补偿 | `0x5010` | INT16，EV×1000，`0xFFFF`(=-1) 为未定义 | — | `协议` |
| 遥控拍摄 | `InitiateCapture` | 非 DeviceProp，由通道 `supportsCapture` 声明 | — | 实测（电脑遥控模式） |

写入通道：`0x9205 SDIO_SetExtDevicePropValue` 优先，失败回退 `0x9207 SDIO_ControlDevice`
（见 `PtpIpClient.setDeviceProperty`）。该路径只支持 2/4 字节值宽度，因此相机上报其它宽度时
能力被判为 `READ_ONLY` 而不是猜着写。

## 4. 传输通道能力

| 通道 | 相机模式 | DeviceProp 参数 | 遥控拍摄 | 证据 |
|---|---|---|---|---|
| PTP/IP 15740，功能模式 0（选片集） | 智能手机连接 / 电脑遥控 | 按描述符判定 | 支持 | 实测 |
| PTP/IP 15740，功能模式 1（整卡） | 电脑遥控 | 按描述符判定 | `未验证` | — |
| UPnP 64321 | 发送到智能手机 | `UNSUPPORTED`（结构性不暴露） | `UNSUPPORTED` | 实测 |
| BLE 快门 | 蓝牙遥控 | 不适用（另一条通路） | 支持（两段快门/录像） | 实测 |

BLE 相机状态（ff02 通知：对焦/快门/录像）是**三态**：`null` 表示尚未收到通知或已断线。
断线后置 `false` 会在界面显示「未录像」——那是伪造事实，相机可能仍在录制。

## 5. 未验证项

以下各项**没有实现为可用功能**，也不得据本文档推断其可行：

1. **白平衡 / 曝光补偿的描述符形式**。0x5005 是否上报枚举表、0x5010 是否上报 Range，
   均无真机记录。若相机只报 `GetSet=0x01` 而不给取值形式，这两项会显示为
   「相机上报可写，但未给出可选档位」并禁用。这是有意为之：此前的硬编码档位表
   （7 档白平衡、19 档 ±3.0EV）从未与相机核对过。
2. **ISO / 快门的标准属性码回退**。标准 PTP 的 `0x500F ExposureIndex` 与
   `0x500D ExposureTime` 可能是部分机型的真实通道，但 `ExposureTime` 的值语义是毫秒
   而非索尼的分子/分母打包。未经真机确认前不做候选回退——猜错编码比「未知并禁用」更糟。
3. **换镜头是否触发 0xC203**。若不触发，档位表要等下一次重连或属性变化才刷新。
4. **整卡模式下的属性可写性**。整卡会重建 PTP 会话，相机在该模式下是否仍接受 0x9205 未知。
5. **对焦模式 / 坐标对焦 / 电动变焦 / 连拍 / B 门 / 间隔**。全部属于 T4 的协议验证范围，
   本版本未引入任何相关能力项。
6. **其它机型与固件**。能力快照只在当次连接内有效，不做跨设备持久化（T6 的范围）。

## 6. 采集新机型的快照

连接相机后进入遥控页，抓取 tag 为 `camera` 的日志：

```
能力快照 ZV-E10|2.03|PTP_IP|0（descriptorRead=true stale=false）
  ISO = WRITABLE（证据=DEVICE_PROP_DESCRIPTOR，0xD21E GetSet=0x01 且 IsEnabled=1）
  F_NUMBER = WRITABLE（证据=DEVICE_PROP_DESCRIPTOR，0x5007 GetSet=0x01 且 IsEnabled=1）
  ...
```

把整段贴进本文档第 3 节的对应行并标注机型/固件/模式即可。`descriptorRead=false` 说明
0x9209 读取失败，那份快照不能作为「不支持」的证据。
