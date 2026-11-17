# Parvagram feature specification

Status: approved development scope; implementation must be verified phase by phase.
Target branch: `feature/contact-activity-smart-proxy-voice-ghost`

This document defines expected behavior. It does not authorize merging or stable release.

## 1. Ghost mode: voice and round-video read-receipt fix

### Problem
In ghost mode, playing an incoming voice message through normal playback can clear the sender-side unread/listened indicator. Preview playback currently behaves as expected.

### Required behavior
- Audit every path that marks incoming voice messages and round videos as content-read, including normal playback, preview, replay, downloads, notifications, and other media entry points.
- Reuse the existing `AyuGhostUtils` policy and Telegram/Nagram read-receipt lifecycle.
- When ghost mode blocks content-read packets, local playback must not send a content-read receipt to the sender.
- When ghost mode is disabled, preserve normal Telegram behavior.
- Do not suppress unrelated receipts unless the existing setting explicitly covers them.
- Avoid breaking local playback progress or UI state.

### Acceptance checks
- With ghost mode enabled, play an incoming voice normally and verify from a second sender account that the voice remains unlistened.
- Repeat with preview playback, round video, pause/resume, replay, and app background/foreground.
- With ghost mode disabled, verify the sender receives the expected receipt.
- Verify regular message read receipts and unrelated media behavior remain unchanged.

A source-code guard alone is not proof; sender-side verification with two accounts is required.

## 2. Contact activity center and profile timeline

### Existing foundation
Extend `ContactChanges` and its existing UI. Do not create a second contact-change observer or event store.

### Required profile events
Record changes when the client actually observes them:
- Profile photo changed or removed, if the API exposes that state.
- First name / last name changed.
- Username changed.
- Bio changed, only when the relevant profile data is available to this account.
- Phone number changed, only when Telegram exposes it to this account.

For each event store account ID, contact user ID, event type, observed timestamp, previous/new values where available, and source/provenance.

### UI
- Global and per-contact timelines, newest first.
- Filter by event type/contact; search where consistent with existing UI.
- Show old/new values and dates without inventing unavailable values.
- Mark entries as observed locally, not recovered historical events.
- Provide clear-history controls with confirmation.

### Acceptance checks
- Each supported observed change creates one event.
- Unchanged/repeated updates do not create duplicates.
- Account switching never mixes timelines.
- Missing/inaccessible fields are not shown as known values.

## 3. Profile photo archive for all contacts

### User-selected default
Archive profile photos for **all contacts** by default, with controls for selected contacts or disabled mode.

### Required behavior
- Detect a new profile-photo version through existing Telegram contact/profile updates.
- Save a local copy only when the photo is accessible and successfully downloaded.
- Keep historical versions with observed/downloaded timestamps.
- Link records to account ID, contact user ID, Telegram photo/version identity where available, and local file metadata.
- Deduplicate using stable identifiers and/or content hashes.
- Provide per-contact gallery and current-versus-previous comparison.
- Allow per-contact inclusion/exclusion, global disable, retention period, and storage quota.
- Perform bounded, cancellable background downloads; respect network availability.
- Clean orphaned/expired files safely; never delete current profile assets or unrelated app files.
- Keep image bytes in app-private file storage and metadata in the existing suitable persistence layer.

### Constraints
- Do not claim to recover photos never available to this client.
- Do not poll Telegram aggressively. Prefer observed updates and bounded synchronization consistent with API limits.
- Handle app restarts, partial downloads, duplicate updates, account removal, and storage-full conditions.

### Acceptance checks
- New photo is archived once; identical updates do not create duplicate files.
- Previous archived photos remain visible after a profile change.
- All-contacts, selected-contacts, and disabled modes work.
- Quota/retention cleanup removes only eligible archive data.
- Background work is bounded and does not block chat processing.

## 4. Last observed activity

### Existing foundation
Extend `LocalLastSeen`; preserve current UI and semantics unless an explicit improvement is required.

### Required behavior
- Record only activity signals the client actually receives and may observe, such as visible online/offline status and typing events.
- Store account ID, user ID, event type, local observation time, server timestamp if supplied, and source.
- Keep event types distinct: typing is not proof of reading, and reading is not proof of opening a chat.
- Use precise wording such as "last observed"; do not imply unsupported exact online history.
- Respect Telegram privacy restrictions and avoid high-frequency polling.

### Acceptance checks
- Every displayed activity entry corresponds to a received Telegram update.
- No event is fabricated when status is hidden/unavailable.
- Account data is isolated and duplicate updates handled.

## 5. Outgoing-message read-receipt history

### Required behavior
- Observe Telegram's existing read-history updates for messages sent by the current account.
- Record chat/contact, message identifiers or safe range representation, and time the receipt update was received.
- Display this separately from presence/activity.
- Reuse existing update handlers and message state; do not send extra requests or receipts to create this history.
- Do not store message text unless strictly necessary.

### Acceptance checks
- Record only after the corresponding Telegram update is received.
- Repeated/cumulative read updates do not create duplicate entries.
- Ghost mode behavior is unchanged; this feature must not send a read receipt.
- History remains associated with the correct account and chat.

## 6. Silent chat/profile view detection: explicit limitation

Telegram's standard client API does not provide a reliable event that tells this account another user merely opened its profile or opened a chat without an observable action.

Do not implement or advertise a profile-visitor or silent-chat-opener tracker. Do not infer such a visit from online status, typing, read receipts, profile changes, or network timing. These signals are distinct and insufficient.

## 7. Smart V2Ray connection

### Existing foundation
Extend `ProxyRotationController`, `V2RayTransport`, `V2RaySubscriptions`, and `ProxyVpnGuard`. Do not add a competing rotation loop or duplicate health checker.

### Required behavior
- Measure candidate latency and actual Telegram connectivity where feasible.
- Track consecutive failures, connection success, recovery time, and recent stability.
- Rank proxies using a documented score prioritizing reliable connectivity over one low ping.
- Switch only after confirmed failure/degradation and only to a validated candidate.
- Use hysteresis, cooldown, bounded retries, and a maximum switch frequency to prevent flapping.
- If candidate fails, safely restore previous working proxy or fall back to existing user-selected behavior.
- Manual user selection takes precedence unless automatic switching is explicitly enabled.
- Coordinate with system VPN detection and existing proxy/subscription settings.
- Reuse existing parallel-test limits and avoid excessive network/battery use.
- Explain switches in UI/history without exposing proxy credentials in logs.

### Acceptance checks
- Stable active proxy is not replaced by a marginally faster but unstable one.
- Repeated failures trigger controlled failover.
- Failed candidate does not leave app disconnected indefinitely.
- Manual mode never silently switches.
- VPN guard, subscriptions, backgrounding, and account switching continue to work.

## 8. Implementation order

1. Audit and complete ghost voice/round-video receipt fix.
2. Complete per-contact timeline using `ContactChanges`.
3. Implement bounded profile-photo archive for all contacts.
4. Extend observed-activity history using `LocalLastSeen`.
5. Add outgoing-message read-receipt history using existing Telegram updates.
6. Upgrade existing proxy rotation into smart V2Ray failover.
7. Run regression tests, CI build, and real-device/two-account verification where required.

Each phase must be independently committed and tested before moving on. If an API limitation blocks a phase, document it and continue only with independent work that does not compromise correctness.

## 9. Completion report

For every phase report existing code reused, files changed, commit SHA, behavior implemented, limitations, tests actually run/results, real-device tests needed, and remaining issues.

Never claim the scope is complete until all phases and acceptance checks are addressed.
