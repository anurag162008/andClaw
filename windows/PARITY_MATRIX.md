# Android vs Windows Feature Parity Matrix

Source of Android feature list: root `README.md` feature section.

| Android Feature | Windows Status | Implementation / Replacement |
|---|---|---|
| One-tap setup for rootfs, Node.js, system tools, OpenClaw, and Playwright Chromium | Implemented (auto-provision + verification) | `SetupService` + Setup tab can install runtime and verify WSL/runtime dependencies |
| OpenRouter OAuth onboarding or manual API-key setup | Implemented | Onboarding tab + `OAuthService` |
| Gateway lifecycle control from the Android UI | Implemented | Dashboard tab + `WslGatewayService` start/stop/restart |
| Provider and model configuration for OpenRouter, OpenAI, Anthropic, Google, and OpenAI Codex mode | Implemented (config-level) | `AppConfig` + provider/model fields in onboarding/settings |
| Messaging channel integration for WhatsApp, Telegram, and Discord | Implemented (config + tokens), Android-specific pairing replaced | channel toggles/token settings + `CapabilityService` replacement notes |
| WhatsApp QR pairing flow from inside the app | Replaced on Windows | webhook/cloud bridge guidance (no Android WebView pairing path) |
| Runtime recovery support through foreground service, boot auto-start, app-update restart, and watchdog recovery | Replaced on Windows | Runtime Doctor + Windows Startup integration (`WindowsStartupService`) |
| Play Asset Delivery support for large install-time assets | Replaced on Windows | direct local runtime validation + WSL checks |

## Important

Windows parity here means **functional equivalence with platform-appropriate replacements** where Android-only APIs are unavailable.
