# Privacy model

Domain AI's promise: **nothing leaves your device unless you explicitly say so, and
when it does, you see exactly what.**

## Guarantees and how they're enforced

| Guarantee | Enforcement |
| --- | --- |
| Local by default | `PrivacyRouter` answers locally unless a per-message cloud action is taken. There is no code path that auto-escalates to cloud. |
| Kill switch is absolute | `NetworkGuard.assertNetworkAllowed` throws unless the switch is off **and** consent is granted. `RemoteEngine` is only reachable through the router, which calls the guard first. |
| Consent is explicit | `cloudConsentGiven` defaults to `false`. Until granted in Settings, no message can leave the device. |
| Sent-payload transparency | Every cloud reply shows the exact redacted text that was sent (`Sent (redacted): …`), so the data that left the device is visible. |
| Redaction before cloud | `PiiRedactor` strips emails, SSNs, cards, IPs, and phone numbers from the prompt **and** the conversation history sent upstream. |
| Encrypted at rest | Conversations: AES-256 `EncryptedFile`. Provider credentials: `EncryptedSharedPreferences`. Keys live in the Android Keystore (hardware-backed where available). |
| No exfiltration via backup | `android:allowBackup="false"` plus explicit exclude rules in `data_extraction_rules.xml`. |
| No tracking | No analytics SDKs and no third-party trackers. Crash reporting (Firebase Crashlytics) is **opt-in and off by default**, sends only crash/error reports when enabled, and is entirely inert unless a Firebase config is added. The only other network is the opt-in cloud provider and the explicit model download. |
| Model download is opt-in and content-free | Downloading a model transmits **no chat content** — only fetches public weights from an allow-listed host, and only after an explicit confirmation dialog. It is deliberately **not** gated by the inference kill switch (see below). Importing a `.gguf` is fully offline. |

## Defaults (most private possible)

```kotlin
PrivacyState(
    networkKillSwitch = true,   // fully offline out of the box
    cloudConsentGiven = false,  // cloud cannot be used at all yet
    redactBeforeCloud = true,   // PII stripped if/when cloud is enabled
)
```

## Model download vs the kill switch

`NetworkGuard` guards **outbound inference** — user content leaving the device. A
model download carries no user content, so gating it behind the inference kill
switch would be the wrong trade: it would force users to enable cloud-inference
consent just to fetch a local model, weakening what the kill switch means. Instead
the download is its own explicit, one-time, allow-listed action behind a clear
confirmation dialog, and **import remains the fully-offline path** for users who
want zero network ever. The literal "kill switch ⇒ zero packets" guarantee is
relaxed only for this single user-initiated fetch; the spirit — no data
exfiltration, no silent/background traffic — is preserved.

## Threat model notes

- **On-device compromise (root / physical):** conversation data is encrypted at
  rest, but a fully compromised device can still observe plaintext in memory while
  the app is in use. This is out of scope for an app-level design.
- **Network observer:** with the kill switch on (default) there is no traffic to
  observe. With cloud enabled, traffic goes over HTTPS to the configured endpoint;
  the redactor reduces sensitive content but is heuristic, not a guarantee against
  every form of PII.
- **The redactor is conservative but not infallible.** It favors over-redaction,
  and every cloud reply surfaces the exact redacted text that was sent.
