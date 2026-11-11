# Development validation record

## Final validated development artifact

Implementation is available for device review. CI-verifiable checks below passed; full real-device/two-account acceptance is still pending. This is a development artifact, not a stable release.

- Validated source commit: `18cf10a431a8586246086fe7757309a5ca35e209`.
- Final CI: https://github.com/mostafagl/Nagram/actions/runs/42932183104.
- APK bundle (arm64-v8a, armeabi-v7a, x86_64): https://github.com/mostafagl/Nagram/actions/runs/42932183104/artifacts/13816863034.
- Exact arm64 file: `Parvagram-dev-arm64-18cf10a4.apk`; 80,650,135 bytes.
- Arm64 APK SHA-256: `dd2045160677f51fa8e8cce7cd48b4d97dc7794fae514b55c0ce6bb29c67231a`.
- Draft PR: https://github.com/mostafagl/Nagram/pull/2. No merge or release performed.

| Validation | Result |
| --- | --- |
| Java/Kotlin compilation and development APK assembly | Passed |
| Native artifacts for three ABIs | Existing cache restored successfully |
| All APK signatures against expected application certificate | Passed |
| Ghost receipt policy regression tests | 3 passed |
| Contact migration/archive cleanup regression tests | 2 passed |
| Observed activity/read-watermark regression tests | 3 passed |
| Proxy health regression tests | 2 passed |
| Total targeted unit tests | 10 passed; 0 failed, 0 errors, 0 skipped |
| Emulator launch/process/crash smoke check | Passed |
| Broader inherited emulator UI suite | Finished; workflow job is green, but its own summary reports login-dependent failures (see below) |
| Real-device/two-account feature acceptance | Pending; checklist below |

The final test XML was retrieved from artifact `13818185152` and inspected. The final smoke step and signature steps completed successfully. CI release and screenshot-publication steps are disabled for this branch. Later documentation-only commits do not alter the APK source.


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
| Synchronous check handling and scan efficiency | `67e24d280dac447ec43fefbe07ba1e45106a12cc` |
| Timeline navigation and supported test SDK | `81ef78dbdacfb9a39b357181eaec9ba4de7468e0` |
| Bounded avatar file copying / final code | `18cf10a431a8586246086fe7757309a5ca35e209` |

Activity CI: https://github.com/mostafagl/Nagram/actions/runs/42930322551. Smart-proxy CI: https://github.com/mostafagl/Nagram/actions/runs/42930916018. Final results and the APK artifact are recorded at the top of this report.

- Activity build compiled successfully and passed the three ghost policy tests; Robolectric rejected the default target SDK before executing the two Android-context test classes. Those preference/file tests now explicitly use SDK 28 (within the existing supported application range). App compile/target/min SDKs are unchanged.
- Final UI review moved timeline actions into a labelled overflow menu, added direct activity/receipt settings entries, and replaced raw photo-ID display with archived/current image comparison.
- Avatar copying uses bounded standard file streams (also suitable for Android 23–25), rejects empty data and keeps atomic temporary-file replacement.

## Files changed by this development session

The concurrently added repository instructions/specification were preserved and are not counted as implementation authored in this session.

- `.github/workflows/pr.yml`
- `TMessagesProj/build.gradle`
- `TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/ProxyRotationController.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/SecretChatHelper.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java`
- `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Components/PopupAudioView.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java`
- `TMessagesProj/src/main/java/org/telegram/utils/proxy/V2RayTransport.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/ContactChanges.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/LocalLastSeen.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/ContactChangesActivity.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/ParvaSecuritySettingsActivity.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/utils/AyuGhostUtils.java`
- `TMessagesProj/src/main/res/values-fa-rIR/strings_na.xml`
- `TMessagesProj/src/main/res/values/strings_na.xml`
- `TMessagesProj/src/test/AndroidManifest.xml`
- `TMessagesProj/src/test/java/org/telegram/messenger/ProxyRotationHealthTest.java`
- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/helpers/ContactChangesTest.java`
- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/helpers/LocalLastSeenTest.java`
- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/utils/AyuGhostUtilsTest.java`
- `docs/DEVELOPMENT_PROGRESS.md`

## Device acceptance checklist

- With two real accounts, verify voice/round-video listened receipts in normal playback, preview, popup, replay/autoplay, secret chats and background/foreground. Compare ghost on/off and explicit-read actions. Source/unit tests cannot establish the sender-side result.
- Observe each contact profile field changing once, repeated updates, missing/private fields, account switching/removal, all/selected/excluded photo modes, rapid photo versions, offline/restart/storage-full cases, retention/quota cleanup and current/archived comparison. Only accessible received avatars can be retained.
- Confirm activity provenance against actual received updates, hidden status producing no invented presence, cumulative private-dialog read ranges, repeated/sync-replayed receipts and separate deletion scopes. Receipt observation time is not exact reading time.
- With actual V2Ray configs, test stable connection, failing core startup, Telegram connectivity failure after core startup, cooldown and recovery, manual rotation off, VPN up/down, subscriptions refreshing, backgrounding and account switching. The previous core is preserved on startup failure; successful switching can still briefly interrupt transport.
- No reliable profile-view or silent-chat-entry tracking is implemented or claimed.

## Completed emulator UI run: important distinction

The workflow job completed successfully, but its script summary contains failed scenarios. Test-server login was rejected with `Wrong code`, so peer messaging, chat history, chat preview, Persian settings and font checks were not verified. A green job is not proof those scenarios passed.

The script reports passing app launch, opening/parsing VLESS, Telegram connection through VLESS, proxy-list/ping controls, subscription import with a five-config limit and duplicate skipping, connection notification and process survival after 60 seconds in the background. The single ping check completed with `No response`; this is not evidence of a successful ping. Smart failover and sender-side ghost privacy still require the device/two-account acceptance checklist above. No new APK is needed for this documentation update.
