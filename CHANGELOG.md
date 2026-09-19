# Changelog

All notable changes to Domain AI are documented here. This project adheres to
[Semantic Versioning](https://semver.org/).

## [1.11] — 2026-09-14

### Performance
- **q8_0 KV cache.** Long-context decoding on a phone is bound by memory traffic more
  than arithmetic, so the key/value cache is now kept quantized — roughly halving that
  traffic and freeing RAM a larger context can use instead. Models that can't support
  it (no flash attention, or a head dimension that doesn't divide the block size) fall
  back to the previous full-precision cache automatically.
- **Right-sized prompt compute buffer.** The physical batch was tracking the
  device-adaptive logical batch, so a high-RAM phone reserved a compute buffer sized
  for 4096 tokens in exchange for prefill gains that had long since flattened. It is
  now capped independently.
- **The dot-product kernels are back, without dropping a single device.** ggml's
  accelerated integer kernels are compile-time gated, so one binary has to either
  name a high CPU baseline and exclude older phones or name none and leave the
  kernels out for everyone. The engine now ships the CPU backend built once per
  feature tier and picks one at runtime: a modern chip gets dot product, fp16, i8mm
  or SVE as available, and an older one gets exactly the kernels it has today. The
  selected build is shown in Settings → System info.
- **llamafile/tinyBLAS `sgemm` enabled** — upstream's own default, previously off only
  to keep the cross-compile lean. It supplies the blocked matmul path that prompt
  prefill leans on, for well under 1 MB of APK. Its quantized ARM kernels gate on dot
  product, so per-tier dispatch is what lets a capable device reach them.
- **Faster cold start.** `Application.onCreate()` was doing a surprising amount of work
  before the first frame: Firebase initialized its entire SDK from a ContentProvider on
  every launch, three separate encrypted stores each built a hardware-backed master key
  and opened a Tink keyset, one of them read and decrypted the profile list
  synchronously, and two more reads went to `/sys` and `/proc`. All of it is lazy now
  and warmed on a background coroutine, so it still runs early but no longer blocks the
  launch. Firebase is not initialized at all unless crash reporting is switched on —
  the right default for an app that ships no telemetry, quite apart from the speed.
- **Baseline profile.** Release builds now carry ART rules for the startup path, so a
  first launch runs AOT-compiled instead of being JIT'd. Debug builds are unaffected.

### Changed
- **First-run screen leads with the promises, not the paperwork.** It opened with
  roughly 440 words, all expanded: an intro, the full Terms, the full Privacy Policy
  and a consent note. Now three single-line guarantees come first, with both legal
  documents still present in full — collapsed, under an explicit "by continuing you
  accept both documents below". Accepting is unchanged: a deliberate tap on a
  labelled button.
- **Light-mode contrast fix.** `brand_cloud` was `#B26A00`, which measured 3.97:1
  against the light page background. It is used for small text — the Cloud routing
  badge, the "Active" profile label, the "Heavy for this device" chip — where WCAG
  AA requires 4.5:1, not the 3:1 that applies to icons. Darkened to `#8F5500`
  (5.50:1 worst case). Dark mode was already compliant.
- **No CPU requirement beyond baseline arm64.** On-device inference runs on every
  arm64 device the app supports, the Snapdragon 835 and Exynos 8895 included — now
  by selecting a CPU build per device rather than by leaving the fast kernels out.
- **Larger download.** Shipping a CPU backend per feature tier costs APK size. It
  buys back speed on hardware that can use it while keeping every older device
  working, which a single binary cannot do.

### Added
- **Inference settings now adapt continuously, not once at startup.** Auto thread
  count, context length and prompt batch size are re-decided at every model load from
  the phone's live state — free memory rather than just total RAM, plus thermal
  throttling and battery saver. An 8 GB phone with 500 MB free is treated as a small
  device; a throttling SoC gets fewer threads, because workers stalled on a hot core
  add contention without adding tokens. Memory pressure moves the ceilings (it clamps
  an explicit choice too, since a context free RAM can't back won't load); thermal and
  battery saver only bias Auto, so a thread count you picked yourself is kept.
- **Starter prompts in an empty chat.** A new conversation used to be a heading and
  a paragraph restating the privacy model — already said on the first-run screen, and
  no help to someone wondering what to type. It now offers four tappable prompts, each
  a different shape of task. Tapping one fills the input rather than sending it, since
  people usually want to adjust the wording first.
- **System info in Settings.** What the app detected about your device and how it
  decided to run: CPU cores and the decoded feature list (including whether the chip
  has dot product), the inference engine's own build flags and loaded backends, total
  and free memory, thermal and battery-saver state, and the plan in force — with the
  reason it was scaled back, when it was. One tap copies the lot as plain text for a
  bug report.
- **Regenerate a reply.** Every finished reply carries a regenerate control: the answer
  is discarded and the same question is asked again. Routing is decided afresh, so
  regenerating after switching profile, model, context length or threads uses the new
  settings — useful when an on-device answer came out truncated, repetitive or wrong.
- **Edit and resend a question.** Any of your messages can be reworded and asked again.
  The conversation continues from that turn, so the answers that followed are replaced;
  the dialog says how many messages that is before you confirm. Editing the opening
  question also frees the chat's title to follow the new wording.
- Your own messages now have a copy control too, alongside edit.

### Internal
- `tools/check-contrast.py` checks every foreground/background pairing in both
  palettes against the WCAG floor that applies to how each one is actually used —
  4.5:1 for the brand colours because they render as small text, not the 3:1 that
  would have let the bug above pass. It runs in CI, before the build, because it
  needs no Android SDK and guards the palette nobody develops in.
- 32 unreferenced string resources removed (264 → 232): leftovers from a pre-send
  cloud review dialog, an older privacy banner and a previous model picker, all of
  whose code is long gone. None had a caller in Kotlin or XML.
- Both new actions rewind the conversation and then go back through the ordinary send
  path, so routing, redaction, history budgeting and encrypted persistence behave
  identically to a fresh message. The rewind rules (what is dropped, what the rolling
  summary may still claim, when a chat's title is released) are pure functions on the
  model, covered by JVM unit tests.
- The "one generation at a time" guard moved into the view model, shared by send,
  regenerate and resend instead of being re-checked per entry point.
- The adaptive policy is a pure function over a plain device snapshot (`Adaptive.plan`),
  so it is unit-tested on the JVM — including an exhaustive sweep over the input space
  asserting its invariants. `ModelManager` now holds a plan *provider* rather than the
  five fixed integers it used to be constructed with, which is what makes re-planning
  possible at all. `effectiveContextTokens()` reports the window the loaded context
  actually has, so history budgeting can't drift from it between loads.
- **Per-tier CPU dispatch, and the two failed attempts before it.** Upstream selects
  a CPU variant by globbing a directory for `libggml-cpu-*.so` and scoring each
  candidate. That cannot work on Android: with `extractNativeLibs=false` the `.so`
  files are stored uncompressed *inside* the APK, so the directory is empty and the
  scan finds nothing — `no backends are loaded`. The libraries are named instead.
  `CpuVariant` turns `AT_HWCAP`/`AT_HWCAP2` into an ordered candidate list ending in
  the armv8.0 baseline, `System.loadLibrary` pulls each one in (the platform loader
  being the part that can map a library out of an APK), and the JNI then loads by
  bare soname against what is already resolved. ggml re-scores every candidate as it
  loads, and upstream compiles the score function without architecture flags
  precisely so it cannot fault on an older CPU, so a wrong tier costs a rejected
  load rather than a crash. Two subtleties: `MODULE` libraries go to CMake's
  *runtime* output directory, which is not where AGP collects native libraries from,
  and `ggml_threadpool_*` lives inside the backend, so core pinning is recovered by
  `dlsym` rather than lost.
- **The earlier history, kept because the reasoning still matters.** They are compile-time
  gated on `__ARM_FEATURE_DOTPROD`, which only `-march` defines, so a cross-compile
  naming no target omits them. `GGML_CPU_ALL_VARIANTS` (build one CPU backend per
  feature tier, pick at runtime) is the correct fix and failed on device: it requires
  `GGML_BACKEND_DL`, whose registry discovers backends by scanning a filesystem
  directory that modern Android packaging never populates. Raising the fixed baseline
  to `armv8.2-a+dotprod+fp16` worked instead — and excluded this project's primary test
  device, because **dot product is optional in ARMv8.2 and mandatory only from
  ARMv8.4**, so 8 GB of RAM implies nothing about it. The build stays at `armv8-a`.
  `CpuFeatures` remains as a startup diagnostic (`AT_HWCAP` from `/proc/self/auxv`,
  decoded into the log) and gates nothing. `docs/ARCHITECTURE.md` carries the full
  autopsy and the bare-soname route that would sidestep the directory scan.

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
