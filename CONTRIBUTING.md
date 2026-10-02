# Contributing 贡献指南

Thanks for your interest in improving Imagedge! / 感谢你愿意为 Imagedge 出力！

## How to report a bug / 如何反馈问题

Open an issue with:

1. Phone model + Android version (e.g. Pixel 8 / Android 16)
2. Camera model + firmware (e.g. ZV-E10 / 2.03)
3. Which camera mode was used (Send to Smartphone / PC Remote / Bluetooth Remote)
4. Steps to reproduce, expected vs actual behavior
5. If possible: `adb logcat` output filtered by the app's log tag `CamRemote`

## How to contribute code / 如何提交代码

1. Fork → create a branch from `alpha` (`feat/xxx` or `fix/xxx`) — `alpha` 是本仓库唯一的一条线（没有 `main`）
2. Follow the existing code style (see below) — the project uses PBF (package by feature) and ktlint-friendly formatting
3. Keep protocol-level changes honest: if you discover a device quirk or a new opcode/event,
   record it next to the constant it belongs to, and mark clearly whether you verified it on real hardware
4. Make sure `./gradlew :app:assembleDebug test :app:uiSpecCheck` passes — `uiSpecCheck` rejects raw Material 3
   controls under `feature/`; use the `App*` components instead
5. Open a PR describing what changed **and how it was verified**. If you could not test on a
   real camera/phone, say so explicitly and say what you did test — an unverified claim
   written as a verified one is worse than an honest gap

## Code style / 代码风格

- Kotlin official style; class header comment template (author/time/desc/version)
- Package by feature under `com.imagedge.camera`; feature UI in `feature/<name>`, data in `data/`
- Pure protocol modules (`:core` `:ptp` `:upnp` `:liveview` `:raw` `:lut` `:motionphoto`) stay free of Android & DI dependencies — DI bindings live in `:app` (`injection/AppModule.kt`)
- Strings in `res/values/strings.xml` (user-facing), constants in `Config.kt` / companion objects

## Protocol research / 协议研究

Sony's wireless protocols are partly undocumented. This repository does not publish a protocol reference: findings belong beside the constant they justify (see `ptp/src/main/java/com/imagedge/camera/ptp/`), each marked with whether it was verified on real hardware. A claim that reads as verified but is not is worse than an honest gap — so if you reverse-engineer something new (Wireshark on official app traffic, BLE captures, etc.), say how you know, and say which parts remain untested.
