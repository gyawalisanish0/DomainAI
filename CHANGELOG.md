# Changelog

All notable changes to Domain AI are documented here. This project adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Regenerate a reply.** Every finished reply carries a regenerate control: the answer
  is discarded and the same question is asked again. Routing is decided afresh, so
  regenerating after switching model, context length or threads uses the new settings —
  useful when an on-device answer came out truncated, repetitive or simply wrong.
- **Edit and resend a question.** Any of your messages can be reworded and asked again.
  The conversation continues from that turn, so the answers that followed are replaced;
  the dialog says how many messages that is before you confirm. Editing the opening
  question also frees the chat's title to follow the new wording.
- Your own messages now have a copy control too, alongside edit.

### Internal
- Both new actions rewind the conversation and then go back through the ordinary send
  path, so routing, redaction, history budgeting and encrypted persistence behave
  identically to a fresh message. The rewind rules (what is dropped, what the rolling
  summary may still claim, when a chat's title is released) are pure functions on the
  model, covered by JVM unit tests.
- The "one generation at a time" guard moved into the view model, shared by send,
  regenerate and resend instead of being re-checked per entry point.

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
