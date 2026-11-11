# Development validation record

Target: `feature/contact-activity-smart-proxy-voice-ghost`; initial head `0a9d757647abc67b5543964f8051ac74369a7032`.

## Initial audit

- Read `docs/DEVELOPMENT_RULES.md`; no `AGENTS.md` exists in the checked-out repository.
- Reviewed the preceding 12 commits, including the initial voice guard and both contact timeline changes.
- Existing owners: `ContactChanges` (snapshot/event persistence and timeline), `LocalLastSeen` (latest observed timestamp), `ProxyRotationController` (rotation), `V2RayTransport` (core), `AyuGhostUtils` (receipt permits).
- Main branch is 56 commits behind the initial target branch. No upstream merge or release is requested.
- CI automatically published pre-releases and screenshot commits: disabled both for this development branch before invoking CI.

## Stage 1: ghost voice receipts

Commit: `5c7801abb743cc437089f09bbc538f069eada7b5`.

- Guard content-read mutations and timed-media task creation, plus secret-chat receipt generation.
- Separate a non-consuming policy peek from actual transport permission consumption; unrelated RPCs no longer consume a one-shot read permit.
- Include encrypted read history in the transport gate. Normal mode retains upstream behavior.
- Added regression tests for permit exhaustion, non-consuming checks, invalid counts and unlimited permits.
- Local Gradle attempt: blocked before configuration by `Network is unreachable` downloading Gradle 8.13. No local Android build claimed.
- CI run: https://github.com/mostafagl/Nagram/actions/runs/42927220112 (pending at stage-2 implementation).
- CI result: `assembleRelease` PASSED (14m 47s). The follow-on unit-test task failed before running tests because the test manifest lacked the main manifest override for MLKit minimum SDK 24. Added the same override to `src/test/AndroidManifest.xml`; app minimum SDK remains 23. Run 42928869271 confirmed that same test-infrastructure failure, not a source compilation failure.
- Required device test: sender-side listened indicator for voice/round video, preview, autoplay, secret chats, timed media and explicit read actions, with ghost toggles before/during playback.
- Extended audit found popup playback directly clearing the local unread flag; guarded that path too. Deferred timed-media callbacks recheck policy when executed, so enabling ghost mode after opening a viewer is respected.
- Local isolated Java check PASSED against the actual `AyuGhostUtils` source (Android/Telegram dependencies stubbed): blocked/default policy, non-consuming UI checks, one-shot consumption, zero/unlimited counts, normal mode, and concurrent one-shot consumption from 20 threads. This is policy verification, not an Android integration or sender-side test.
- While CI was running, remote commits `631f6e34` and `55d9949e` added `AGENTS.md` and `docs/FEATURE_SPEC.md`. Both were read and preserved. The unvalidated activity implementation was set aside locally until preceding phases are validated.
- CI now runs the targeted unit tests before APK packaging for faster feedback. Only this development branch explicitly disables R8/resource shrinking; ordinary release defaults stay optimized. Development APKs retain the existing variant and signing verification and are uploaded as private workflow artifacts, not published as releases.

## Stage 2: profile timeline and received-photo archive

Commit: `9f359b0df004877473aede1b41aac6fbc80afed6`; CI: https://github.com/mostafagl/Nagram/actions/runs/42927716520.

- Extend `ContactChanges`; no parallel observer, timeline store or download worker.
- Preserve legacy snapshots while migrating to JSON (profile text can contain the old separator).
- Record bio changes only after a bio baseline was actually received; retain old/new photo IDs and independent simultaneous photo/block evidence.
- Existing all-contact/selected-contact switch controls capture. Receive visible avatar versions through the existing FileLoader, one at a time; bounded queue, cooldown after failures, deduplication by user/photo ID.
- Copy received photos to app-private account storage; retain at most 500 files / 128 MiB. This archives observed avatars, not hidden photos or deleted versions never received by this client. Oldest files may be evicted by quota.
- Browse retained images from the existing changes screen, globally or for the selected contact.
- Added migration/text-integrity regression test. Local Gradle build/test attempt again blocked downloading Gradle; CI validation required.
- Required device test: initial contact sync, over 256 contacts, simultaneous photo/bio changes, failed downloads/restart, two accounts, archive opening and quota eviction.
- After reading the new feature specification, added independent all-contact archive enable (default on), shared contact-selection controls, per-contact exclusions, configurable days/quota, observed/downloaded/file metadata, image comparison, archive deletion confirmation and account-removal cleanup.
- Archive cancellation invalidates queued/copy completions without cancelling shared FileLoader requests used by the normal UI. Cleanup touches only archive-owned filenames, removes orphan metadata/partial copies and never touches Telegram profile caches.
- Profile events persist provenance; the timeline supports a display type filter. Added quota/retention/file-boundary regression coverage.
- Local isolated test PASSED for the actual extracted Snapshot implementation: legacy/JSON migration, Persian/control-character/newline integrity, unknown/empty bio distinction and malformed input. English/Persian resource XML parsing and diff whitespace checks passed. Android integration remains subject to CI/device verification.
- Local isolated archive-cleanup test PASSED using the actual extracted cleanup implementation: expired-photo deletion, oldest-first quota eviction, partial/orphan cleanup and unrelated-file preservation. Preferences were stubbed; this is not an Android/FileLoader integration test.
- Retrying a failed photo version is bounded by cooldown; a genuinely new photo version bypasses that old-version cooldown. Concurrent queued versions are deduplicated by user/photo identity rather than user alone.

## Stage 3: observed activity and outgoing read watermarks

- Extend `LocalLastSeen` with one bounded 500-event history per account and explicit event provenance. Keep existing latest timestamps; late events cannot move them backwards.
- Record received message, non-cancel typing and Telegram-provided online/offline events. Disabling local last seen stops new collection. No profile visit/chat-entry inference.
- Store cumulative private-dialog read watermarks with the newly covered ID range and device observation time. These do not provide exact per-message reading times; groups and secret-chat per-message receipts are not invented.
- Reuse the contact timeline screen for global/per-person activity history and deletion. Retention, timing limits and provenance are disclosed in English/Persian UI.
- Regression tests cover account separation, late events, disabled collection, read-range boundaries, regressive/duplicate watermarks and bounded retention.
- Local build/test remains blocked before project configuration by the unavailable direct Gradle download. CI build/test queued on commit; sender/account activity and sync replay still require an authenticated device test.
- Activity and read-receipt history have separate views and scoped deletion. Account removal clears both persisted history and cached timestamps. Regression selection includes the existing three targeted test classes; no repeated broad test expansion.

## Stage 4: smart rotation and safe V2Ray handover

- Extend `ProxyRotationController`, keeping its existing switch setting/timeout and existing transport test executor (four V2Ray tests concurrently). No second rotation loop or health worker.
- Session score (lower is better): latency EWMA + 10,000 ms per consecutive failure + bounded recovery-time penalty + missing successful-check/Telegram-connection penalties. A healthy active connection is never replaced for a marginally better ping.
- Fresh successful checks expire after two minutes. Failed candidates receive 30–120 second cooldown; switches are limited to one per 30 seconds. Each round checks at most 25 candidates and has a 60-second coordination deadline; shared in-flight transport tests are not duplicated/cancelled.
- Observe actual Telegram connection recovery and invalidate batches on manual selection, account changes, proxy disable/VPN suspension or connection recovery. All account slots reuse the same controller.
- Start a replacement V2Ray core before retiring the previous core. Rotation validates core startup before changing persisted selection; failed startup leaves the prior transport/selection intact. A later Telegram connectivity failure returns to bounded candidate selection rather than assuming a successful core start proves connectivity.
- Explain automatic switch reason in the existing proxy screen; native status text is no longer logged because it may contain server details. Existing subscription handling is unchanged.
- Essential regression tests cover stability versus a single fast ping, latency smoothing, failure cooldown and actual connection recovery. Android/Xray/VPN/background handover requires device integration testing; no claim of zero packet loss during a successful switch.
- Final review also guards synchronous proxy-test completions while a batch is being assembled. Contact scans compare snapshot fields directly and reuse the per-scan exclusion set instead of serializing JSON/copying settings for every unchanged contact.

## Published development commits

| Area | Commit |
| --- | --- |
| Ghost transport/storage guards | `5c7801abb743cc437089f09bbc538f069eada7b5` |
| Initial photo retention and bio timeline | `9f359b0df004877473aede1b41aac6fbc80afed6` |
| Popup/deferred ghost guard | `f167bc63a3da6e677ee58014cd474fbf92ea5b86` |
| Development build/test pipeline | `90480f15baadb2edb53dc8eef749796995fd6bf3` |
| Complete archive controls/provenance | `82a5176c9281ce9cb775247c09de767abe5989b1` |
| Activity and separate receipt view | `9b2cd82027bf3ed1deb13948d67b32ca3c579b62` |
| Smart rotation and transport handover | `7fe5b11e6962cc622861fcdea682cbfdd7e843f7` |

Activity CI: https://github.com/mostafagl/Nagram/actions/runs/42930322551. Smart-proxy CI: https://github.com/mostafagl/Nagram/actions/runs/42930916018. Final results and APK artifact will be recorded after the current validation completes.

- Activity build compiled successfully and passed the three ghost policy tests; Robolectric rejected the default target SDK before executing the two Android-context test classes. Those preference/file tests now explicitly use SDK 28 (within the existing supported application range). App compile/target/min SDKs are unchanged.
- Final UI review moved timeline actions into a labelled overflow menu, added direct activity/receipt settings entries, and replaced raw photo-ID display with archived/current image comparison.
