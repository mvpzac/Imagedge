# Imagedge

[English](README.md) | [简体中文](README_zh-CN.md)

<div align="center">

![Version](https://img.shields.io/badge/version-0.2.0--alpha08-1A1B1E?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-64--bit%20only-black?style=flat-square)

</div>

A third-party, open-source Android app for wireless transfer and remote control of Sony cameras, built with **Kotlin + Jetpack Compose (Material 3)**.

> **Transfer, remote control and provisioning are field-tested on a Sony ZV-E10 (firmware 2.03).** Most features should also work on other Sony models with the same wireless modes — contributions and compatibility reports are welcome.
> The editor's newest batch (undo/redo, presets, crop frame folded into the recipe) has only been exercised on an **emulator with a debug build**; the real-device + release pass is still outstanding. See the [unverified list in the CHANGELOG](CHANGELOG.md) item by item.

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
- Whole-file streaming download (stability first: `GetPartialObject` chunked transfer was rejected by the camera on the tested model, so the chunked path is kept in `:ptp` but disabled)
- **RAW (ARW)** — embedded full-size JPEG preview extracted on-device for instant large view
- Downloads land in the system gallery (`DCIM/Imagedge/` by default) or any user-picked SAF directory, registered in MediaStore with `IS_PENDING` + capture time, so the gallery sorts by shooting time and never shows half-written files

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
- **Edit & Adjust** (the main editor) — the editing state collapses into **one recipe** (`EditRecipe`, an ordered `EditStep` list), and undo/redo is a **cursor over that recipe** rather than a second command stack. Three sections in one screen:
  - **Color** — built-in film-style creative presets + S-Log2/S-Log3 → Rec.709 conversion LUTs, `.cube` import/export/delete. Filters are shown as **live thumbnails rendered from your own photo**; a **luminance histogram** sits above the sliders (normalised by the tallest bucket, so clipping is obvious); strength plus **seven axes** — exposure / highlights / shadows / tint / contrast / saturation / temperature — are applied together with the filter in a single pixel pass
  - **Crop** — aspect presets (free / 1:1 / 4:3 / 3:2 / 16:9 / 9:16) and a draggable crop frame (corner handles + move by dragging inside). The frame is the recipe's **last geometry step** and travels with rotate/flip through history — kept outside the recipe, "undo the rotation, then export" silently produces a region you never framed
  - **Rotate** — 90° left/right, horizontal/vertical mirror (the two directions toggle independently), and **straighten** (−45°..45°, auto-cropping the blank corners)
  - **Undo / redo** — one entry per slider release, aspect pick, flip or mirror; loading a photo seeds the "as loaded" entry, so your very first edit can be undone
  - **Presets** — save the colour + filter look, apply it to another photo (its geometry is kept), long-press to delete; overwriting a same-named preset asks first. On disk it is one recipe as text (`filesDir/edit_presets/<name>.json`); unknown keys, unknown steps and out-of-range numbers are **refused with a reason**
  - The preview can be compared against the **colour-applied-off** version (long-press is the shortcut, and there is an explicit button too — long-press is undiscoverable and unreachable for screen readers)
  - Export re-renders at **full resolution** (geometry first, then color), with format / quality / EXIF policy shared verbatim with the share sheet, and preserves EXIF
- **GPU-accelerated**: LUT is applied through an OpenGL ES 3.0 3D texture (hardware trilinear filtering, no NDK), with an automatic pure-Kotlin CPU fallback when EGL / shaders / texture limits are unavailable
- **LIVE-photo triptych** — stitch up to 3 LIVE photos/videos into a single motion photo (unified aspect ratio + per-slot alignment, cover frame, audio toggle and order), with a WYSIWYG preview and a result screen
- **EXIF camera frame** — 5 templates (classic white border / dark bar / floating polaroid / two-line signature / minimal overlay) with brand logo, model, focal length, aperture, shutter, ISO and capture time auto-filled from EXIF, plus custom text (signature / location / ©), per-field toggles, rounded corners and EXIF preserved on export

**Export**
- **Video → Motion Photo (LIVE Photo)** — pick one or more videos, trim each clip (≤ 5 s), pick a cover frame (auto = mid-frame), then package into a single-file motion photo readable by Google / OPPO / Xiaomi galleries (`:motionphoto` module, Media3 `MuxerUtil`)
- **Export & share** (`:share`) — size tiers (original / 2048 px / 1080 px / 2 MP), format (JPEG / PNG / WebP), quality, and an EXIF privacy policy (keep all / strip GPS only / strip everything). Exports are cache copies, orientation-normalized, handed to the system share sheet — no extra permissions, no third-party SDK

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
| `:lut` | LUT engine: `.cube` parser + GPU processor (OpenGL ES 3.0 3D texture) with CPU trilinear fallback; holds the **single Kotlin definition** of the colour/keying maths — the GLSL is a hand-written mirror of it |
| `:motionphoto` | Video → Motion Photo (LIVE Photo) packaging (Media3 `MuxerUtil`) |
| `:image` | Edit recipe + geometry pipeline: ordered `EditStep` list, cursor-based history (undo/redo), normalised crop coordinates. **No pixel work here** — everything per-pixel belongs to `:lut` |
| `:share` | Export & share: size tiers, formats, EXIF privacy policy, system share sheet |
| `:app` | Compose UI (MVVM + Hilt), BLE shutter, download manager, settings |

Deep dives:

- [Architecture & data flow](docs/architecture.md)
- [Sony wireless protocol notes](docs/sony-protocol-notes.md) — BLE shutter codes, QR format, LiveView framing, content-transfer pitfalls (hard-won, field-verified)

## Requirements

- Android 10+ (minSdk 29), targetSdk 37
- **64-bit devices only** (arm64-v8a / x86_64); 32-bit ABIs are not supported
- A Sony camera with Wi-Fi "Send to Smartphone" / "PC Remote" / "Bluetooth Remote" functions
- JDK 21, Android SDK (compileSdk 37)

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
- [x] Export & share with size tiers, formats and EXIF privacy policy (`:share`)
- [x] Non-destructive edit recipe + geometry (`:image`)
- [x] Undo / redo (a cursor over the recipe) and preset save / apply / delete
- [x] Video preview in the fullscreen viewer
- [x] Auto pull-back of remotely captured photos (PTP path)
- [ ] Local range keying (exposure / contrast / saturation applied only inside a
      luma / hue / saturation range) — the keying maths and the preset codec are in,
      but the **UI and the export path are not wired yet**, and the CPU/GPU parity
      test has been written without ever being run on a device
- [ ] libraw-based true RAW decoding (NDK)
- [x] GPU LUT processor (OpenGL ES 3.0 3D texture, CPU fallback)

## Contributing

Issues and PRs are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Protocol knowledge that benefits everyone (verified device quirks, new model compatibility) is especially appreciated.

## License & disclaimer

- Code: [Apache-2.0](LICENSE)
- Third-party components and their licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Bundled LUT presets (`app/src/main/assets/luts/`): 8 creative looks generated by [EditClips](https://editclips.online) (free to use) + S-Log2/S-Log3 → Rec.709 conversion LUTs generated in-house with colour-science (Sony published transfer functions) — see each file's header comment
- **Disclaimer**: this project is not affiliated with or endorsed by Sony Corporation. Sony and related marks belong to their owners. Protocol knowledge comes from public documentation and community reverse-engineering, provided for learning and interoperability purposes. Use at your own responsibility and comply with local laws.
