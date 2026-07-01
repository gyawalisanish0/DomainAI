# Changelog

All notable changes to Domain AI are documented here. This project adheres to
[Semantic Versioning](https://semver.org/).

## [1.08] — 2026-06-28

### Added
- **Self-hosted Space backend (backend v0.33).** A FastAPI server (`backend/`) runs a
  llama.cpp model directly inside a Hugging Face Docker Space via llama-cpp-python,
  exposing an OpenAI-compatible `/v1` API. Models are stored on HF persistent storage
  (`/data/models`) — downloaded once, reused across restarts. Supports team mode (one
  Space, one `SPACE_TOKEN`, multiple Android clients) and community forking. The backend
  exposes a curated model catalog rated against the Space's available RAM.
- **Space model picker in Settings.** A new "Space backend" section replaces the
  previous HF Serverless section. Enter your Space URL and `SPACE_TOKEN`, tap "Connect"
  to verify the link, then browse the Space's curated catalog with hardware suitability
  badges (Recommended / Heavy / Not enough RAM). Tap "Load" on any model to trigger an
  on-Space download with real-time percentage progress streamed live to the app, followed
  by automatic provider activation once the model is ready.
- **Multi-model profile system.** You can now save any number of named inference
  configurations — Space backends, OpenRouter models, and custom endpoints — and
  switch between them (and local on-device models) with a single tap in the chat
  inference panel. No credential re-entry after the first setup: Space URL/token and
  OpenRouter key are stored once and survive profile switches.
- **One-tap model picker redesigned.** The chat model picker now groups options into
  three sections: *On-device* (installed GGUF models), *My server* (Space profiles),
  and *Cloud API* (OpenRouter and custom endpoint profiles). Each profile is shown by
  its readable name; the active one is checkmarked.
- **Saved profiles list in Settings.** A new "Saved profiles" section lists every
  cloud configuration with the ability to switch, rename, or delete entries inline.
  The active profile is highlighted; deactivating it routes the next chat on-device.
- **Credential persistence across profiles.** Space URL + token and OpenRouter API key
  are stored in separate encrypted slots, independent of which profile is active. The
  Space section shows the connected host with a one-tap reconnect; the OpenRouter
  section shows "key saved" status with a remove option.
- **First-launch migration.** If the app was previously configured with a single cloud
  provider, that config is automatically imported as a named profile on first launch so
  no settings are lost.

### UX
- **Instant feedback on send.** The user's message appears immediately when Send is
  tapped — no blank screen while history preparation runs. An animated typing indicator
  (three pulsing dots in a reply bubble) shows during the gap before the first token
  arrives, so the app always feels responsive.
- **Smooth streaming for cloud providers.** Replies from OpenRouter and custom endpoints
  now render character by character at a steady pace instead of popping in all at once
  when a burst of tokens arrives. Any remaining buffer after the model finishes drains
  quickly so the last sentence is never held up.

### Internal
- `Role.ORACLE` renamed to `Role.DOMAIN` to match the app's current name.
- `smoothStream()` Flow extension in `ChatRepository` buffers incoming chars and
  releases them at 18 ms/char during a burst, 6 ms/char on final drain, and 0 ms when
  the stream is trickling (local / server) so no artificial delay is added there.

### Performance
- **Adaptive prompt-prefill batch size.** N_BATCH is now chosen at startup from device
  RAM instead of a hardcoded 512 — the same tiers on both Android and the Space backend:
  < 8 GB → 512, 8–16 GB → 1024, 16–32 GB → 2048, 32 GB+ → 4096. Flagship phones and
  server-class Space hardware process long prompts significantly faster.

### Internal
- `DeviceCapabilities.recommendedBatchSize()` threads through `ModelManager` →
  `LLamaAndroid.load(nBatch)` → the `new_context` JNI call; `completion_init` reads it
  back from the context via `llama_n_batch(ctx)` — no extra parameter needed.
- Space backend: GPU layer ladder (`[99, 32, 24, 16, 12, 8, 4, 0]`) auto-selects GPU
  offload depth; steps down on OOM so the Space starts on CPU-only hardware. AVX2/FMA/F16C
  compile flags enable faster CPU prefill. `asyncio.Lock` serializes concurrent clients.
  Cache-first downloads check for an existing file before fetching; writes are atomic
  (`.tmp` rename) to avoid corrupt models on interrupted downloads.
- New `ModelProfile` data class and `ProviderType` enum (`SPACE`, `OPEN_ROUTER`,
  `CUSTOM`) as the canonical representation of a saved inference config.
- New `ModelProfileStore` backed by `EncryptedSharedPreferences` — stores the full
  profile list (JSON), the active profile ID, and separate credential slots for Space
  and OpenRouter credentials. Exposes `StateFlow<List<ModelProfile>>` and
  `StateFlow<String?>` for reactive UI updates.
- `validateAndSave()` in `SettingsViewModel` now creates a `ModelProfile` on
  successful round-trip validation and sets it active; the profile id replaces the
  bare `preferCloud` boolean as the routing signal.

## [1.05] — 2026-06-25

### Added
- **Generation keeps running in the background.** A long on-device reply (and a model
  download) now continues when you leave the app, under a foreground service that
  holds the process at priority so Android doesn't kill it mid-way — with a "Generating
  reply…" / download-progress notification while it runs. Swiping the app away from
  recents stops it.

### Performance
- **Adaptive, core-pinned generation threads.** Thread count is now probed from the
  device's CPU at startup instead of a hardcoded 4: Auto picks a middle-ground count —
  about half the cores (an 8-core phone uses 4), leaving the rest free for the UI. The
  threads are also **pinned to the fastest cores** so generation stays on the powerful
  cluster instead of drifting onto the little cores (best-effort; some Android
  schedulers may override the affinity request). Flash attention was verified already
  auto-enabled.
- **User-configurable generation threads.** A new "Generation threads" setting (in
  Settings and the chat quick-panel, alongside context length) lets you override the
  adaptive count: Auto, or a fixed 2–6 bounded by your device's cores. The chosen
  count pins to that many of the fastest cores, so picking fewer keeps generation on
  the big cluster. Changing it reloads the model.

### Fixed
- **Consistent model selection.** The chat top-bar subtitle, the quick-panel
  checkmark, and Settings' "In use" now always agree on the active on-device model,
  including while a model is loading.
- **A failed download no longer disrupts the model you're using.** A download or
  import that fails surfaces its own error and leaves the loaded model untouched.

### Changed
- **Downloads and imports are their own visible process.** Acquiring a model now
  shows real progress ("Downloading X — 42%" / "Importing X…") in both chat and
  Settings, distinct from "Loading on-device model…" — which now means only loading
  into memory.

### Internal
- All user-facing strings moved to `res/values/strings.xml` (the pure-Kotlin,
  JVM-tested privacy core stays Android-free by design).
- Docs: refined README (badges, release link, tech-stack table), added
  `docs/MODEL_SELECTION.md`, generalized model-specific references, and corrected the
  cloud-privacy description (PII redaction + the post-send "Sent (redacted)" badge;
  the see-before-send dialog was removed).

## [1.01] — 2026-06-23

First public release.

### Features
- **Local-first chat** — replies generated on-device by default with zero network
  access; the cloud is strictly opt-in per message.
- **Network kill switch** — a single, code-enforced chokepoint that makes outbound
  requests impossible; on by default.
- **See-before-send** — shows the exact redacted payload before any cloud call and
  waits for confirmation.
- **PII redaction** — strips emails, phone numbers, SSNs, cards and IPs before a
  cloud request.
- **Routing transparency** — every reply is badged *On-device*, *Cloud*, or
  *Blocked*.
- **Encrypted at rest** — conversations stored in an AES-256 file keyed by the
  Android Keystore; backups disabled; no analytics/trackers (crash reporting is
  opt-in, off by default).
- **GPU acceleration** with an in-app CPU-vs-GPU benchmark.
- **User-configurable context window** (Auto or fixed, bounded by device RAM) from
  Settings and a chat quick-panel.
- **On-device model management** — download a GGUF model in-app or import your own.
- **Reply length follows the context window** — output is bounded by the chosen
  context size rather than a fixed token cap, so long answers finish on their own.

### Fixed
- Chat history no longer vanishes after closing and reopening the app. The
  encrypted store wrote conversations under a temporary filename and renamed it,
  but `EncryptedFile` binds ciphertext to the filename, so the committed file
  could not be decrypted on the next launch. Writes now target the canonical name,
  with crash-safe backups and recovery of previously unreadable history.

### Requirements
- Android 8.0+ (API 26), arm64-v8a devices only.
