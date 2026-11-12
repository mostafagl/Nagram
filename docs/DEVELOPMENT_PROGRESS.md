# Development validation record

## Parallel proxy, subscription and GIF corrections — integration validation pending

The user explicitly authorized parallel implementation, independent review, testing, then an ordinary Latest Release. No merge is authorized. This section supersedes earlier pending-scope notes below.

- Reused the existing proxy screen, subscription worker and rotation controller. Fixed toolbar callback replacement; clipboard HTTP(S) subscription links open the existing subscription editor with the URL prefilled.
- Manual ping, subscription final check and deletion share one bounded four-worker batch. Foreign automatic checks are awaited without dropping entries. A six-minute watchdog finishes once and ignores late results. Direct-route denial/unsupported transport is not a dead-server result and cannot enter deletion evidence. User selection/preferences changing during the operation prevent automatic reconnection. Editing a config while a check runs cannot apply the old config's ping to the new one.
- Manual and refreshed-subscription results select the lowest successfully measured server through the existing lifecycle. Active VPN guard policy remains respected; UI explains when auto-connect is skipped. Subscription refresh preserves saved entries, deduplicates and only adds functionally working candidates up to the existing configured quota. Failed or empty results have explicit summaries. Deletion shows first/second-pass counts and asks confirmation for the twice-failed subset; denial of a direct route aborts deletion.
- V2Ray direct checks use isolated per-test sockets and Android physical-network binding, including physical-network DNS. The pinned Xray dependency receives a small per-token hook; neither the process network nor the active Telegram core is rebound. Android lockdown/no route/DNS denial/native hook absence/zero bound sockets return unavailable. Failed multi-address DNS probes return unavailable conservatively because this patch does not implement full address fallback. Legacy non-V2Ray checks retain the existing checker only when no VPN is active; under VPN they report unsupported direct measurement.
- Raw GIF89a remains in the animated-image path even when the video-style GIF preference is enabled; only animated MP4 qualifies for the video player. Both gallery classification and initial autostart use the same policy. The supplied sample decoded completely using ffmpeg (77 frames, 600x338, 6.16s); no sample media is committed. Runtime authenticated channel playback still needs a phone check.
- Existing ShowDeletedMessages and ForceCopy already default to true and are reused. Received deletion IDs/push updates retain locally received messages with the existing deleted mark. No fabricated actor or separate reliable “partner cleared whole chat” event is introduced. Server-side deletion cannot be prevented. Saved media-copy filenames now include account index to prevent cross-account collisions; old files remain untouched.

Independent review addressed caller-scope mutation/skipped-check false success, progress operation ownership, native fd ownership, VPN/DNS route leakage, unavailable-vs-dead semantics, stale selection/config edits, one-shot callbacks and synchronous checker failures.

Local validation: Java syntax parsing, shell syntax, Python AST, diff whitespace and pinned native patch-anchor dry-run passed. The local Gradle distribution could not be downloaded; there is no local Go compiler (the installed `go` is an unrelated game). Actual Go helper tests, gomobile AAR build, Android compilation, regression XML results, signature verification and emulator launch smoke must pass in CI before publication. Final results, source/run IDs and APK checksums will be recorded after that validation. Device-dependent acceptance remains separate from CI.

---

## Latest requested corrections — published ordinary Release

Source: `31f3a0702a83c3c0ca0424ab6dc1d0b8fde12c81`.
Build: https://github.com/mostafagl/Nagram/actions/runs/43123540262.
Publisher: https://github.com/mostafagl/Nagram/actions/runs/43123533796.

The user explicitly requested an ordinary Latest Release, not a pre-release. Publication is gated on all three native jobs and the Gradle job (focused regression tests, optimized APK assembly, signature checks and emulator launch smoke) completing successfully. No merge is authorized.

Current validation: PASSED for the release build checks. Native builds for all three ABIs, Java/Kotlin compilation, `testReleaseUnitTest`, `assembleRelease`, APK signatures and emulator launch smoke all passed. The build log explicitly confirms `minifyReleaseWithR8`; resource shrinking is enabled in the release configuration.

Test XML from artifact `13903600784` was downloaded, its ZIP SHA-256 verified (`6232f2fd8c3935e8084b4c925cb9e1389a1f3ea0b74ca879937ff779bacfec81`) and inspected:

| Regression class | Passed | Failures/errors/skips |
| --- | ---: | ---: |
| AyuGhostUtilsTest | 3 | 0 |
| ContactChangesTest | 2 | 0 |
| LocalLastSeenTest | 2 | 0 |
| ProxyRotationHealthTest | 2 | 0 |
| V2RaySubscriptionNamesTest | 1 | 0 |
| Total | 10 | 0 |

APK artifact: `13902463450`, ZIP SHA-256 `5914d90e1029b10889cc96a47e9cb07025ba0ee53cc1517d193add3d8e1fb69e`. Publisher independently verified that artifact digest, APK signatures and the absence of a debuggable application flag. Expected signing certificate SHA-256: `3b76c1cdce57941b2b24a5973fedf787c8af82956f32e7f1b44c0663fe78d9ab`.
Release: https://github.com/mostafagl/Nagram/releases/tag/build-1794441705607 . Confirmed through `/releases/latest`: `draft=false`, `prerelease=false`, target source `31f3a0702a83c3c0ca0424ab6dc1d0b8fde12c81`. Publisher run `43123533796` succeeded. The release uses the existing `build-<timestamp>` updater format and includes `build_timestamp.txt` plus checksums. No merge was performed.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| Parvagram-arm64-v8a.apk | 64,488,908 | `3c178902995a6b22541685589f39a5256738b092b337ad2373f07703ec4a1844` |
| Parvagram-armeabi-v7a.apk | 63,298,726 | `c72b3e770f2987bcf331411fd1ad59931bd842214337facaeb97a1e52130e339` |
| Parvagram-x86_64.apk | 66,160,899 | `23ce19f7b1df3749ba9452637d75a325f3c44d676121f9683c85d665216c2263` |

The broader inherited authenticated emulator suite is separate from the release launch smoke. Its account-dependent results are not claimed as acceptance of the requested touch/Telegram-account scenarios.

### Resulting behavior

- Main filter tabs (including Users with All hidden) expose the existing archive row/pull animation. The gesture follows the existing Telegram parallax, quick pulls use the distance threshold, and cancelled gestures snap back. Short-list scroll range and pinned-chat reordering remain supported.
- Proxy toolbar: refresh updates all saved subscriptions through the existing cancellable workflow; if there are no subscriptions, it opens their management screen. Ping uses the existing checker and does not duplicate checks already running. Unset sort preference defaults to ascending ping; explicit existing sort choices are preserved. Results re-sort with a 200 ms debounce.
- One-link-per-line subscription names preserve unescaped spaces after # for plain and base64 bodies. The generic V2Ray suffix is removed from titles. Update the subscription after installing to recover previously truncated names. The existing protection against replacing an active config remains: its saved link/name is refreshed after switching away and updating again.
- Typing/message/activity timelines and outgoing read-watermark history are removed from collection and navigation. Old history preferences are cleared on access. The original approximate latest observed timestamp and real profile/photo changes remain.
- Nagram-branded launcher variants are hidden. Existing enabled legacy aliases migrate to the Parvagram default; manifest aliases remain for upgrade compatibility.

### Commits for this request

- `c88e3bf9`: archive pull in filters, proxy names/controls, activity/read-history removal, launcher selection cleanup, targeted regression coverage and optimized CI build.
- `ab56fa7d`: short-list archive range and CI concurrency policy.
- `d7eaecc4`: gated stable publisher.
- `a9760454`: prevent publishing-only commits from cancelling their source build; validate the exact source.
- `31f3a070`: preserve pinned-chat reordering for presentation copies.

Earlier CI attempts were cancelled when a newer source superseded them. A publishing-only commit initially retriggered the PR build under the concurrency policy; that orchestration error was corrected. No cancelled build was released. Local Gradle failed before configuration because its distribution download had no network route. Java syntax checks and git diff --check passed; Android test/build results come from the final CI run above.

### Files changed for this request

- `.github/workflows/pr.yml`
- `.github/workflows/publish-validated-preview.yml`
- `TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsAdapter.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Cells/AppIconsSelectorCell.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Components/Premium/PremiumAppIconsPreviewView.java`
- `TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/LauncherIconController.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/V2RaySubscriptionsActivity.java`
- `TMessagesProj/src/main/java/org/telegram/utils/proxy/V2RaySubscriptions.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/LocalLastSeen.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/ContactChangesActivity.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/ParvaSecuritySettingsActivity.java`
- `TMessagesProj/src/test/java/org/telegram/utils/proxy/V2RaySubscriptionNamesTest.java`
- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/helpers/LocalLastSeenTest.java`
- `docs/DEVELOPMENT_PROGRESS.md`

### Remaining device acceptance

- Filtered-tab archive drag on a signed-in phone: RTL, short/long lists, gesture cancellation, fast pull, switching tabs and moving pinned chats.
- Toolbar refresh/ping on a real subscription/network, lowest-ping ordering, active-config name refresh after switching away, and VPN transitions.
- Launcher migration on the phone's OEM launcher.
- Sender-side voice/round-video listened markers with a second account while ghost mode is enabled, including autoplay and secret chats.

The inherited emulator account login previously failed with Wrong code; green wrapper-job status alone is not evidence that authenticated UI scenarios passed.

---

## Historical validation: previous preview (superseded by the corrections below)

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


## Requested usability corrections — November 2026

Audit: the existing Telegram pull drawable/scroll handler was limited to the All tab, while the screenshot used a generated Users filter. Subscription parsing stopped at whitespace and cut raw fragment names to a flag. Proxy testing, subscription update progress/cancellation, sorting, and launcher aliases already existed and are reused.

Changes:
- Archive row is copied into eligible main filter views without mutating MessagesController collections; the same gesture/parallax and release animation handles these views. Quick pulls honor the configured distance, cancelled gestures snap back.
- Proxy toolbar exposes subscription refresh and ping-all, ascending ping is the unset preference default, and measured results are coalesced for live sorting. In-flight proxy checks are not duplicated.
- Subscription line fragments preserve full spaced names; proxy titles no longer append a generic V2Ray label. Plain/base64 subscription regression coverage added.
- Removed activity/typing/message/read-watermark history collection and all its UI. Existing history preferences are discarded on access; the original latest observed timestamp and actual profile/photo change archive remain.
- Nagram-branded launcher variants are hidden; enabled legacy aliases migrate to the Parvagram default. Aliases remain declared for safe app updates.
- CI now assembles the optimized, non-debuggable release variant with R8/resource shrinking enabled, using the existing signing identity.

Local validation: Java syntax parse and git diff --check passed. Gradle unit-test attempt could not download Gradle 8.13 (Network is unreachable); no local test pass is claimed. CI results and final stable Release link will be appended after completion.

Real-device checks required: authenticated filtered-tab archive pull (short/long lists, RTL, cancel, tab switches), proxy toolbar actions on a real subscription/VPN, launcher migration on OEM launchers, and existing ghost playback with a second account. No fabricated presence/profile-view detection is included. User explicitly authorized an ordinary Release, not a pre-release; no merge is authorized.

CI orchestration correction: a publishing-only commit initially retriggered the PR build and cancelled its predecessor under the new concurrency policy. No APK was published from that cancelled run. The publishing workflow is now excluded from ordinary PR path triggers, and this combined CI-policy commit explicitly waits for its own complete PR build.

Archive integration audit also covered drag-reordering pinned dialogs: filtered views containing the synthetic archive row return a presentation copy, so reorder now writes to the existing DialogFilter owner collection by dialog identity. This preserves upstream pinned-chat movement rather than mutating a temporary display list.


## Raw GIF playback correction (pending Android validation)

The supplied playback sample is GIF89a, 600x338, 77 frames, 6.16 seconds, 2,786,816 bytes. ffprobe recognized GIF and ffmpeg decoded the entire file without errors. No sample media is committed to the repository.

PhotoViewer treated every GIF as video when takeGIFasVideo was enabled. Both initial player autostart and gallery item classification now use the existing MessageObject.isNewGif() MP4/animated predicate. Raw image/gif remains in the upstream animated image path. This does not force autoplay against the user's existing autoplay preferences or claim support for every video codec. Actual channel download and playback with the original Telegram document metadata must still be checked on an authenticated device.

Previous proxy-toolbar correction: commit 2ed18187, CI 43129732337. Remaining scope is still pending: direct-network measurements under third-party VPN, post-test/update automatic connection, clipboard subscriptions, and full-chat deletion handling. Existing ForceCopy and ShowDeletedMessages settings already default to true; do not add duplicates or claim the server identifies deletion actors. No new release has been published for these corrections.
