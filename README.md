<div align="center">

# Domain AI

**Local-first AI chat for Android — your words stay on your device.**

[![Latest release](https://img.shields.io/github/v/release/gyawalisanish0/DomainAI?label=release&color=0B6E4F)](https://github.com/gyawalisanish0/DomainAI/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-0B6E4F)](#requirements)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-0B6E4F)](#requirements)
[![CI](https://github.com/gyawalisanish0/DomainAI/actions/workflows/ci.yml/badge.svg)](https://github.com/gyawalisanish0/DomainAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

</div>

Every message is answered **on-device by default** with zero network access. The
cloud is strictly opt-in — gated by a network kill switch and a PII redactor that
strips sensitive information before anything is sent.

## Features

- **Local by default** — replies generated on-device; no network unless you opt in per message.
- **Network kill switch** — outbound requests made impossible at one auditable chokepoint; on by default.
- **PII redaction** — emails, phones, SSNs, cards and IPs stripped before any cloud call; each cloud reply shows the exact redacted text that was sent.
- **Routing transparency** — every reply badged *On-device*, *Cloud*, or *Blocked*.
- **Encrypted at rest** — AES-256 history keyed by the Android Keystore; backups off; no analytics or trackers.
- **GPU acceleration** with an in-app CPU-vs-GPU benchmark, and a **configurable context window**.

## Tech stack

| Layer | Technology | Version |
| --- | --- | --- |
| Language | Kotlin | 2.0.20 |
| UI | Jetpack Compose (BOM) + Material 3 | 2024.09.02 |
| Build | AGP / Gradle | 8.5.2 / 8.9 |
| SDK | Android min / target | 26 / 34 |
| On-device LLM | llama.cpp (vendored) + NDK / CMake | 26.3.11579264 / 3.22.1 |
| Concurrency | Coroutines + Flow | 1.8.1 |
| Cloud (opt-in) | OkHttp | 4.12.0 |
| Serialization | kotlinx.serialization | 1.6.3 |
| Storage | DataStore / security-crypto (AES-256) | 1.1.1 / 1.1.0-alpha06 |
| Navigation | navigation-compose | 2.8.0 |
| Markdown | markdown-renderer / highlights | 0.30.0 / 0.9.1 |
| Crash reporting (opt-in) | Firebase Crashlytics (BOM) | 33.5.1 |

## Requirements

**Android 8.0+ (API 26)** on an **arm64-v8a** device, plus a GGUF model (download
in-app or import your own).

## Install

Grab the signed APK from the
[latest release](https://github.com/gyawalisanish0/DomainAI/releases/latest) and
sideload it (enable "Install unknown apps" if prompted).

## Build

```bash
./gradlew assembleDebug   # builds llama.cpp for arm64 + APK (needs NDK + CMake)
./gradlew test            # JVM unit tests (privacy / routing logic)
```

Design and privacy details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) ·
[docs/PRIVACY.md](docs/PRIVACY.md).

## License

[Apache 2.0](LICENSE).
