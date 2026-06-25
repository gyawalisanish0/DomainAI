# Changelog

All notable changes to Domain AI are documented here. This project adheres to
[Semantic Versioning](https://semver.org/).

## [1.05] — 2026-06-25

### Fixed
- **Replies no longer cut off mid-sentence.** Output length is now governed by the
  context-window setting instead of a hidden 512-token cap, so long answers finish
  on their own — and a larger context allows longer replies.
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
- **Cloud sends:** PII redaction is applied and each cloud reply shows the exact
  redacted text that was sent; the separate see-before-send confirmation dialog was
  removed.

### Internal
- All user-facing strings moved to `res/values/strings.xml` (the pure-Kotlin,
  JVM-tested privacy core stays Android-free by design).
- Release is now published straight from the Actions "Run workflow" button — no git
  tag or terminal needed.
- Docs: refined README (badges, release link, tech-stack table), added
  `docs/MODEL_SELECTION.md`, generalized model-specific references, and noted the
  Blaze-plan requirement for Firebase Test Lab.

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

### Fixed
- Chat history no longer vanishes after closing and reopening the app. The
  encrypted store wrote conversations under a temporary filename and renamed it,
  but `EncryptedFile` binds ciphertext to the filename, so the committed file
  could not be decrypted on the next launch. Writes now target the canonical name,
  with crash-safe backups and recovery of previously unreadable history.

### Requirements
- Android 8.0+ (API 26), arm64-v8a devices only.
