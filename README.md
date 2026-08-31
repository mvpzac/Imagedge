# Imagedge

[English](README.md) | [简体中文](README_zh-CN.md)

A third-party, open-source Android app for wireless transfer and remote control of Sony cameras, built with **Kotlin + Jetpack Compose (Material 3)**.

> Tested with **Sony ZV-E10 (firmware 2.03)**. Most features should also work on other Sony models with the same wireless modes — contributions and compatibility reports are welcome.

## Features

**Transfer (Wi-Fi)**
- Camera-side selection driven album: pick photos on the camera, they appear on the phone automatically (event-driven instant refresh + 4s polling fallback)
- Browse thumbnails, fullscreen photo viewer with paging, batch download with a queue
- RAW (ARW) support: embedded full-size JPEG preview extracted on-device for instant large view
- Downloads land in the system gallery (`DCIM/Imagedge/` by default) or any user-picked directory, registered in MediaStore

**Remote shooting**
- Live view streaming (~18 fps, JPEG frame extraction tolerant to firmware framing variants)
- Bluetooth remote shutter: system pairing → two-stage shutter (press = AF, release = capture) → video record toggle, over the camera's "Bluetooth remote" GATT protocol
- PTP `InitiateCapture` fallback for cameras without BLE remote

**Connection**
- QR provisioning: scan the pairing QR code shown on the camera screen (`W01:S:…;P:…;C:…;M:…`), parse SSID/password and join the hotspot automatically
- Automatic gateway discovery with manual IP fallback
- 10s keep-alive against the camera's 30s idle disconnect, transaction timeouts with socket force-close self-healing, automatic reconnect

**Editing**
- LUT color grading: built-in S-Log3 → Fujifilm film-simulation presets, `.cube` import/export/delete, strength blending
- CPU trilinear interpolation (pure Kotlin); Vulkan GPU path is planned

**Export**
- Motion Photo (LIVE Photo): pick one or more videos, auto-extract a cover frame, then package into a single-file motion photo readable by Google / OPPO / Xiaomi galleries (`:motionphoto` module, Media3 `MuxerUtil`)

**App**
- Material 3, dark theme first (light/system optional), all credits in [LICENSE](LICENSE)

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
- [ ] libraw-based true RAW decoding (NDK)
- [ ] Vulkan GPU LUT processor
- [ ] Auto pull-back of remotely captured photos (needs Sony "Camera Remote Command" reference)
- [ ] Video preview in viewer

## Contributing

Issues and PRs are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Protocol knowledge that benefits everyone (verified device quirks, new model compatibility) is especially appreciated.

## License & disclaimer

- Code: [Apache-2.0](LICENSE)
- Bundled LUT presets (`app/src/main/assets/luts/`): S-Log3 film-simulation cubes generated with LUTCalc by Ben Turley (see each file's header comment)
- **Disclaimer**: this project is not affiliated with or endorsed by Sony Corporation. Sony and related marks belong to their owners. Protocol knowledge comes from public documentation and community reverse-engineering, provided for learning and interoperability purposes. Use at your own responsibility and comply with local laws.
