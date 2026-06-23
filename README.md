# Domain AI

**Local-first AI chat for Android. Your words stay on your device.**

Domain AI is a privacy-first LLM chatbot. Every message is answered **on-device by
default** with zero network access. The cloud is strictly opt-in and gated behind
explicit consent, a hardware-style network kill switch, and a redactor that shows
you exactly what (redacted) text would leave the device — *before* it sends.

> An Android, local-and-web-API based LLM chatbot application.

## What makes it stand out

| Feature | What it does |
| --- | --- |
| **Local by default** | Replies are generated on-device. No network is touched unless you explicitly opt in per message. |
| **Network kill switch** | A single switch makes outbound requests *impossible*, enforced in code at one auditable chokepoint ([`NetworkGuard`](app/src/main/java/sg/act/domain/privacy/NetworkGuard.kt)) — not by convention. **On by default.** |
| **See-before-send** | When you choose cloud for a message, Domain AI shows the exact redacted payload and waits for your confirmation. No surprise exfiltration. |
| **PII redaction** | Emails, phone numbers, SSNs, cards and IPs are stripped before any cloud call. |
| **Routing transparency** | Every reply carries a badge — *On-device*, *Cloud*, or *Blocked* — so the data path is never ambiguous. |
| **Encrypted at rest** | Conversations are stored in an AES-256 file keyed by the Android Keystore. Backups are disabled. No analytics or trackers; crash reporting is opt-in and off by default. |

## Architecture

```
UI (Jetpack Compose, resource-driven, day/night aware)
        │
   ChatViewModel ──────────────► ChatRepository
                                     │
                              PrivacyRouter  ◄── the policy core
                              /            \
                       LocalEngine        RemoteEngine (opt-in)
                    (on-device, default)   (OpenAI-compatible)
                                     │
                        NetworkGuard + PiiRedactor enforce
                        every privacy guarantee on the way out
```

- **Local by default, never silent escalation.** The router only ever uses the
  cloud in response to an explicit per-message user action; otherwise it answers
  locally — always. See [`PrivacyRouter`](app/src/main/java/sg/act/domain/inference/PrivacyRouter.kt).
- **Real on-device LLM via llama.cpp.** A native `:llama` module compiles
  llama.cpp (vendored in-tree) and runs a quantized **Gemma** GGUF model, streaming
  tokens through [`LlamaCppBackend`](app/src/main/java/sg/act/domain/inference/LlamaCppBackend.kt).
  Until a model is loaded, [`LocalEngine`](app/src/main/java/sg/act/domain/inference/LocalEngine.kt)
  answers with a deterministic offline responder, so the app is always usable.
- **Streaming end-to-end.** Replies stream token-by-token from the engine through
  the router and repository into a live-growing chat bubble.
- **Device-aware models.** The Settings catalog rates each Gemma model
  Recommended / Heavy / Not-enough-RAM against the phone's memory. Import accepts
  any GGUF and is fully offline.
- See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/PRIVACY.md](docs/PRIVACY.md).

## Resource-driven UI

No colors, dimensions, strings, or style attributes are hardcoded in Kotlin.
Everything is sourced from `res/` XML:

- Colors → `res/values/colors.xml` + `res/values-night/colors.xml`
- Dimensions → `res/values/dimens.xml` (semantic spacing scale)
- Strings → `res/values/strings.xml`
- Alphas / counts → `res/values/integers.xml`

Dark/light theming follows the **phone's native state** automatically: the
resource framework serves the right palette and Compose builds its `ColorScheme`
from it — no Kotlin-side branching.

## Build & run

Requires Android Studio (Koala+) or the Android SDK, **plus the NDK and CMake**
for the native `:llama` module (the vendored llama.cpp source is in-tree, so no
submodule init is needed):

- NDK `26.3.11579264`, CMake `3.22.1` (install via SDK Manager).
- The native engine targets **arm64-v8a**, so on-device inference needs a 64-bit
  ARM device. An emulator can build/run the UI; real model inference wants a
  physical device with adequate RAM.

```bash
./gradlew assembleDebug        # build the APK (compiles llama.cpp for arm64)
./gradlew test                 # run JVM unit tests (privacy/routing logic)
./gradlew installDebug         # install on a connected device
```

- **minSdk** 26 · **targetSdk** 34 · Kotlin 2.0 · Jetpack Compose · Material 3
- **Native:** llama.cpp vendored at `llama/src/main/cpp/llama.cpp` (commit pinned
  in `llama/src/main/cpp/LLAMA_CPP_VERSION.txt`)

## Tests

The privacy-critical logic is unit-tested on the JVM (no device needed):

- [`PiiRedactorTest`](app/src/test/java/sg/act/domain/privacy/PiiRedactorTest.kt)
- [`PrivacyRouterTest`](app/src/test/java/sg/act/domain/inference/PrivacyRouterTest.kt)
  — verifies local-by-default, kill-switch enforcement, redaction-before-cloud,
  and graceful fallback.

## License

Apache 2.0 — see [LICENSE](LICENSE).
