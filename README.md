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
- **Self-hosted Space backend** — deploy `backend/` as a Hugging Face Docker Space to run a llama.cpp model you control. Browse a curated model catalog from the app, load models on demand with live download progress, and use it as a private cloud backend. Supports team mode (one Space, multiple clients) and community forking.
- **OpenRouter free-model picker** — connect to free OpenRouter models with one tap.
- **Adaptive performance** — inference threads, context length, and prompt-prefill batch size all scale to your device's hardware at startup; all three are user-configurable.

## Tech stack

### Android app

| Layer | Technology | Version |
| --- | --- | --- |
| Language | Kotlin | 2.0.20 |
| UI | Jetpack Compose (BOM) + Material 3 | 2024.09.02 |
| Build | AGP / Gradle | 8.5.2 / 8.9 |
| SDK | Android min / target | 26 / 34 |
| On-device LLM | llama.cpp (vendored) + NDK / CMake | 26.3.11579264 / 3.22.1 |
| Concurrency | Coroutines + Flow | 1.8.1 |
| Cloud HTTP | OkHttp | 4.12.0 |
| Serialization | kotlinx.serialization | 1.6.3 |
| Storage | DataStore / security-crypto (AES-256) | 1.1.1 / 1.1.0-alpha06 |
| Navigation | navigation-compose | 2.8.0 |
| Markdown | markdown-renderer / highlights | 0.30.0 / 0.9.1 |
| Crash reporting (opt-in) | Firebase Crashlytics (BOM) | 33.5.1 |

### Backend (`backend/`)

| Layer | Technology | Version |
| --- | --- | --- |
| Language | Python | 3.11 |
| API server | FastAPI + Uvicorn | 0.115.5 / 0.32.1 |
| Inference | llama-cpp-python (AVX2/FMA/F16C; CUDA optional) | 0.3.4 |
| Model hub | huggingface_hub | 0.26.5 |
| Deployment | HF Docker Space (port 7860) | — |

## Self-hosted backend

`backend/` is a FastAPI server that runs a llama.cpp model directly inside a
Hugging Face Docker Space. Deploy it once, point the Android app at it, and you
have a fully private cloud backend — your model, your Space, your data.

→ **[backend/README.md](backend/README.md)** — deploy guide, secrets table, local dev.  
→ **[backend/CHANGELOG.md](backend/CHANGELOG.md)** — backend version history.

## Requirements

**Android 8.0+ (API 26)** on an **arm64-v8a** device, plus either:
- a GGUF model (download in-app from Settings → On-device model, or import your own), or
- a cloud provider configured in Settings → Cloud (self-hosted Space, OpenRouter, or a custom OpenAI-compatible endpoint).

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
