# Parvagram development rules

## Audit before implementation (mandatory)

Before adding any feature, search the **current target branch** for existing implementations, including Telegram/Nagram upstream code, Parvagram modifications, helper classes, settings, database entities, notification events, and tests.

1. Trace the existing UI entry point, event source, persistence, and network path.
2. Record what already exists, what is partial, and what is genuinely missing.
3. Prefer extending/refactoring the existing owner rather than creating parallel controllers, duplicate observers, tables, caches, background workers, or proxy health checkers.
4. Keep a single source of truth for settings and stored events. Migrate old data if the schema changes.
5. Review all code paths that can trigger the behavior, not only the first one found. For privacy-sensitive features, distinguish local UI state from Telegram server-side events.
6. Add targeted regression tests and run the available CI build; report untested behavior honestly.
7. Use a feature branch and draft PR; do not merge or publish a stable release without explicit approval.

## Existing implementations to reuse

- `tw.nekomimi.nekogram.helpers.ContactChanges`: contact profile change snapshots/events (photo, name, username, phone, possible block status).
- `tw.nekomimi.nekogram.helpers.LocalLastSeen`: locally observed last-seen data.
- `org.telegram.messenger.ProxyRotationController`: proxy checking and automatic rotation; avoid a second competing rotation loop.
- `org.telegram.utils.proxy.V2RayTransport` and `V2RaySubscriptions`: V2Ray execution, subscription handling and existing test paths.
- `tw.nekomimi.nekogram.utils.AyuGhostUtils`: ghost-mode read packet policy.
- `org.telegram.messenger.MediaController`: voice playback path; verify both content-read receipts and all other media-read paths before claiming privacy behavior is fixed.

## Feature audit checklist

| Requested feature | Existing foundation | Missing work to verify |
| --- | --- | --- |
| Contact profile timeline | ContactChanges events/snapshots | scalable history, UI, missing profile fields |
| Profile photo archive for all contacts | ContactChanges photo IDs | actual file retention, deduplication, bounded background work |
| Last observed activity | LocalLastSeen | reliable event provenance and history |
| Outgoing message read receipts | Telegram message read updates | explicit per-message event history and UI |
| Smart V2Ray | ProxyRotationController + V2Ray transport/subscriptions | health scoring, hysteresis, safe failover |
| Ghost voice playback | AyuGhostUtils + MediaController | audit other read paths and real sender-side test |

**Important:** Opening a chat/profile without any Telegram-observable event cannot be reliably detected. Do not implement speculative "viewer tracking" claims.
