# Imagedge

[English](README.md) | [简体中文](README_zh-CN.md)

<div align="center">

![Version](https://img.shields.io/badge/version-0.1.7-1A1B1E?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-64--bit%20only-black?style=flat-square)

</div>

A third-party, open-source Android app for wireless transfer and remote control of Sony cameras, built with **Kotlin + Jetpack Compose (Material 3)**.

> **Tested with Sony ZV-E10 (firmware 2.03).** Most features should also work on other Sony models with the same wireless modes — contributions and compatibility reports are welcome.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Build](#build)
- [Project status & roadmap](#project-status--roadmap)
- [Contributing](#contributing)
- [License & disclaimer](#license--disclaimer)

## Features

**Transfer (Wi-Fi)**
- **Selection-driven album** — pick photos on the camera, they appear on the phone automatically (event-driven instant refresh + 4 s polling fallback)
- Thumbnail grid, fullscreen photo viewer with paging, batch download with a serial queue
- Resumable chunked download (512 KiB blocks, exponential backoff, up to 5 retries per block)
- **RAW (ARW)** — embedded full-size JPEG preview extracted on-device for instant large view
- Downloads land in the system gallery (`DCIM/Imagedge/` by default) or any user-picked SAF directory, registered in MediaStore

**Remote shooting**
- Live view streaming (camera pushes 18–30 fps, throttled to ~20 fps on-device; JPEG frame extraction tolerant to firmware framing variants)
- **Bluetooth remote shutter** — system pairing → two-stage shutter (press = AF, release = capture) → video record toggle, over the camera's "Bluetooth remote" GATT protocol
- **PTP `InitiateCapture` fallback** for cameras without BLE remote
- Remote parameter control via PTP DeviceProp: ISO / aperture / shutter / white balance / exposure compensation / shoot mode, two-way synced from the camera dial
- Auto pull-back of remotely captured photos (works on the PTP path; the BLE + "smartphone connection" path is limited by camera firmware)

**Connection**
- QR provisioning — scan the pairing QR code shown on the camera screen (Sony `W01:S:…;P:…;C:…;M:…` format; standard `WIFI:` codes also supported), parse SSID/password and join the hotspot automatically
- Automatic gateway discovery with manual IP fallback
- 10 s keep-alive against the camera's 30 s idle disconnect, transaction timeouts with socket force-close self-healing, automatic reconnect

**Editing**
- **LUT color grading** — built-in film-style creative presets + S-Log2/S-Log3 → Rec.709 conversion LUTs, `.cube` import/export/delete, adjustable strength
- CPU trilinear interpolation (pure Kotlin); Vulkan GPU path is planned
- **LIVE-photo triptych** — stitch up to 3 LIVE photos/videos into a single 9:16 motion photo (per-slot cover / audio / order)
- **EXIF camera frame** — stamp a photographer-style frame (brand logo + model, focal length, aperture, shutter, ISO, …) auto-filled from the image's own EXIF onto any photo

**Export**
- **Video → Motion Photo (LIVE Photo)** — pick one or more videos, trim each clip (≤ 5 s), pick a cover frame (auto = mid-frame), then package into a single-file motion photo readable by Google / OPPO / Xiaomi galleries (`:motionphoto` module, Media3 `MuxerUtil`)

**App**
- Minimal black-and-white Material 3 theme — light by default, dark / follow-system optional
- Transfer history (long-press on the download page), permission manager, haptic feedback, foreground-service downloads

## Architecture

Gradle multi-module, feature-first packaging (PBF):

| Module | Responsibility |
|--------|----------------|
| `:core` | Pure-Kotlin basics (stream utils, logging) — no Android deps |
| `:ptp` | PTP/IP protocol stack (ISO 15740), pure Kotlin, incl. Sony SDIO extensions |
| `:upnp` | UPnP/SOAP stack (camera "Send to Smartphone" service) |
| `:liveview` | LiveView stream (raw 60152 socket), pure Kotlin — Sony Camera Web API not used (ZV-E10 exposes no such service) |
| `:raw` | RAW decoding: embedded-JPEG extraction (TIFF container parse); libraw NDK planned |
| `:lut` | LUT engine: `.cube` parser, CPU trilinear processor (Vulkan planned) |
| `:motionphoto` | Video → Motion Photo (LIVE Photo) packaging (Media3 `MuxerUtil`) |
| `:app` | Compose UI (MVVM + Hilt), BLE shutter, download manager, settings |

Deep dives:

- [Architecture & data flow](docs/architecture.md)
- [Sony wireless protocol notes](docs/sony-protocol-notes.md) — BLE shutter codes, QR format, LiveView framing, content-transfer pitfalls (hard-won, field-verified)

## Requirements

- Android 10+ (minSdk 29), targetSdk 36
- **64-bit devices only** (arm64-v8a / x86_64); 32-bit ABIs are not supported
- A Sony camera with Wi-Fi "Send to Smartphone" / "PC Remote" / "Bluetooth Remote" functions
- JDK 21, Android SDK (compileSdk 36)

## Build

```bash
git clone https://github.com/mvpzac/Imagedge.git
cd Imagedge
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or simply open the project in **Android Studio** (Ladybug or newer) and press Run.

## Project status & roadmap

- [x] P0/P1 core loops (connect, album, batch download) field-tested
- [x] BLE remote shutter, QR provisioning, LUT editing
- [x] Video → Motion Photo (LIVE Photo) export (`:motionphoto`)
- [x] Video preview in the fullscreen viewer
- [x] Auto pull-back of remotely captured photos (PTP path)
- [ ] libraw-based true RAW decoding (NDK)
- [ ] Vulkan GPU LUT processor

## Contributing

Issues and PRs are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Protocol knowledge that benefits everyone (verified device quirks, new model compatibility) is especially appreciated.

## License & disclaimer

- Code: [Apache-2.0](LICENSE)
- Bundled LUT presets (`app/src/main/assets/luts/`): 8 creative looks generated by [EditClips](https://editclips.online) (free to use) + S-Log2/S-Log3 → Rec.709 conversion LUTs generated in-house with colour-science (Sony published transfer functions) — see each file's header comment
- **Disclaimer**: this project is not affiliated with or endorsed by Sony Corporation. Sony and related marks belong to their owners. Protocol knowledge comes from public documentation and community reverse-engineering, provided for learning and interoperability purposes. Use at your own responsibility and comply with local laws.
