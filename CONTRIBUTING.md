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

1. Fork → create a branch from `main` (`feat/xxx` or `fix/xxx`)
2. Follow the existing code style (see below) — the project uses PBF (package by feature) and ktlint-friendly formatting
3. Keep protocol-level changes documented: if you discover a device quirk or a new opcode/event, add it to `docs/sony-protocol-notes.md` in the same PR
4. Make sure `./gradlew :app:assembleDebug` passes
5. Open a PR describing what changed and how you verified it on real hardware

## Code style / 代码风格

- Kotlin official style; class header comment template (author/time/desc/version)
- Package by feature under `com.imagedge.camera`; feature UI in `feature/<name>`, data in `data/`
- Pure protocol modules (`:core` `:ptp` `:upnp` `:liveview` `:raw` `:lut` `:motionphoto`) stay free of Android & DI dependencies — DI bindings live in `:app` (`injection/AppModule.kt`)
- Strings in `res/values/strings.xml` (user-facing), constants in `Config.kt` / companion objects

## Protocol research / 协议研究

Sony's wireless protocols are partly undocumented. Verified findings are collected in `docs/sony-protocol-notes.md`. If you reverse-engineer something new (Wireshark on Imaging Edge traffic, BLE captures, etc.), PR it there — this is the most valuable contribution you can make.
