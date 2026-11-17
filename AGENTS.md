# Parvagram repository instructions

## Required reading
- Read `docs/DEVELOPMENT_RULES.md` before changing code.
- Read `docs/FEATURE_SPEC.md` when implementing any feature covered by that specification.
- Follow existing repository conventions and upstream Nagram/Telegram architecture.

## Mandatory workflow
1. Inspect the current branch, recent commits, working tree, and relevant call paths before editing.
2. Search for existing implementations and tests before adding classes, services, observers, storage, workers, or settings. Extend the existing implementation where possible; document why a new abstraction is necessary.
3. Implement one independently testable phase at a time. Keep diffs focused and avoid unrelated refactors.
4. Preserve Telegram/Nagram behavior unless a requested feature explicitly changes it. Do not bypass official message, read-receipt, media, account, or proxy lifecycle handling.
5. Add or update regression tests. Run relevant tests and the available Gradle/CI build after each phase. Never claim a test passed unless it actually ran and passed.
6. Distinguish CI-verifiable behavior from behavior requiring a real Android device or two Telegram accounts.
7. Commit only to the requested feature branch. Use a draft PR. Never merge, publish a stable release, or trigger public release distribution without explicit user approval.

## Privacy and correctness
- Only report Telegram events and data the client actually receives. Never fabricate profile views, silent chat opens, online status, read receipts, or historical data.
- Keep account data isolated by Telegram account and user ID.
- Bound background work, network requests, storage, and battery usage. Handle cancellation, retries, and app restarts safely.
- Protect existing user data during migrations; make migrations backward compatible where feasible.
- Do not log credentials, proxy secrets, message contents, or other sensitive data.

## Existing foundations to audit and reuse
- `ContactChanges`: contact profile change events and snapshots.
- `LocalLastSeen`: locally observed activity/last-seen data.
- `ProxyRotationController`: existing proxy testing and rotation.
- `V2RayTransport` and `V2RaySubscriptions`: Xray transport and subscription handling.
- `AyuGhostUtils`: ghost-mode receipt policy.
- `MediaController`: media playback and content-read receipt path.

These are starting points, not proof that every requested behavior is complete. Trace all relevant call paths and verify actual behavior before modifying or extending them.
