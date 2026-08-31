# Sony Wireless Protocol Notes 索尼无线协议笔记

Field-verified (ZV-E10, firmware 2.03) + cross-checked against open-source
implementations (`alpha-fairy`, `alpharemote`, `furble`, `Sony-ZV-E10-RX`,
`SonyPhotoTransfer`, `libgphoto2`). Add your findings via PR.

以下内容全部为真机实测 + 开源实现互相印证的沉淀。欢迎以 PR 补充你的发现。

## 1. BLE remote shutter（相机「蓝牙遥控」）

- Service `8000ff00-ff00-ffff-ffff-ffffffffffff`, command characteristic
  `0000ff01-0000-1000-8000-00805f9b34fb` (write, 2-byte payload `[0x01, code]`)
- **Code semantics: bit0 = pressed, clear = released**

| code | action |
|---|---|
| 0x09 / 0x08 | shutter full-press / release (0x08 only releases, never triggers) |
| 0x07 / 0x06 | half-press (AF) / half-release |
| 0x0F | record start/stop toggle |
| 0x15 | AF-ON |

- **Reliable capture = two-stage**: press sends `0x07` (AF), release sends
  `0x09` + `0x08`. Sending bare `0x09` is ignored by the AF pipeline; queuing
  all four commands quickly leaves the camera stuck half-pressed.
- **Bonding is mandatory**: writing without a system bond gets the connection
  dropped by the camera after ~2s (GATT status 19).
- Scan filter: manufacturer id `0x012D` (Sony).
- 半按抬起（0x06）在 ZV-E10 上不可靠，勿依赖其恢复状态。

## 2. Pairing QR code（「智能手机连接」屏幕二维码）

- Sony-proprietary `W01` format (NOT the standard `WIFI:` format):
  `W01:S:<hotspot-suffix>;P:<password>;C:<device-name>;M:<MAC-12-hex>;`
- `S` is only the **tail** of the hotspot name; the full SSID is
  `DIRECT-{S}:{C}` (e.g. `DIRECT-ZrE1:ZV-E10`). `C` is the **user-editable
  device name**, not the model.
- `M` is the camera MAC — in practice it did **not** match the hotspot BSSID
  (33s no-match vs 5s candidate with SSID). Use SSID formula first, BSSID as
  fallback.

## 3. WifiNetworkSpecifier lifecycle（Android 配网陷阱）

- The connection exists **only while the `requestNetwork` request is alive** —
  `unregisterNetworkCallback` tears down an established link
  (logcat signature: `App released connected request`).
- Keep the callback after success; release only on explicit disconnect.
- Dual-WLAN devices: always bind the process to the `Network` object returned
  by `onAvailable`, not "the first WiFi in allNetworks" (may be the home AP).
- `requestNetwork` needs `CHANGE_NETWORK_STATE` in the manifest; requesting
  permissions not declared in the manifest is **silently denied** on API 31+.

## 4. LiveView stream（`http://<ip>:60152/liveviewstream`）

- The public `$5hy` framing doc does **not** match ZV-E10 firmware (little
  endian, 136-byte headers observed).
- Robust across variants: scan the raw stream for JPEG boundaries
  (`FFD8` … `FFD9`) and emit frames; use `conflate()` on the UI side to always
  show the newest frame.
- Available in both camera Wi-Fi modes (not PC-remote exclusive).

## 5. PTP/IP content transfer（选片驱动相册）

- `SDIO_SetContentsTransferMode (0x9212)` with `{1,0,0}` is the only working
  parameter set (`{1}` and `{0,0,0}` return 0x201D).
- Virtual storage id `0xF10001` (decimal 15794177) is what `GetStorageIDs`
  returns in this mode; enumerate with parent = `0x0` (whole tree).
- **Handles are reused**: different photos may carry the same object handle.
  Every cache keyed by handle alone (thumbnail cache, LazyColumn keys, download
  queue de-dup) MUST mix in content fingerprint (size + filename).
- Event stream must be consumed: `StoreAdded 0x4004` / `StoreRemoved 0x4005`
  mark the content-set rebuild on each camera-side send; `0xC203` is Sony
  DevicePropChanged (noise). The camera does **not** push
  `RequestObjectTransfer 0x4009` on this firmware.
- Remote-captured (BLE shutter) photos are **not** auto-added to the transfer
  set — auto pull-back requires Sony's official "Camera Remote Command"
  reference.
- Mode matrix: PTP 15740 lives in both modes; card storage over standard PTP
  is locked (`0x2013`) in PC-remote mode; no Sony Camera Web API service
  exists on this firmware — LiveView 60152 is the only HTTP extra.

## 6. Session & concurrency

- Camera drops idle PTP sessions after ~30s → 10s `GetDeviceInfo` keep-alive.
- Never wrap blocking socket IO in `@Synchronized`: a 25MB RAW download holds
  the monitor for tens of seconds and deadlocks poller + keep-alive (waiting
  on monitors is not interruptible and not covered by soTimeout). Use a
  cancellable coroutine `Mutex` + per-transaction timeouts + force-closing the
  socket on timeout + auto reconnect.
