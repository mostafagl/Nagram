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
- Required device test: sender-side listened indicator for voice/round video, preview, autoplay, secret chats, timed media and explicit read actions, with ghost toggles before/during playback.

## Stage 2: profile timeline and received-photo archive

- Extend `ContactChanges`; no parallel observer, timeline store or download worker.
- Preserve legacy snapshots while migrating to JSON (profile text can contain the old separator).
- Record bio changes only after a bio baseline was actually received; retain old/new photo IDs and independent simultaneous photo/block evidence.
- Existing all-contact/selected-contact switch controls capture. Receive visible avatar versions through the existing FileLoader, one at a time; bounded queue, cooldown after failures, deduplication by user/photo ID.
- Copy received photos to app-private account storage; retain at most 500 files / 128 MiB. This archives observed avatars, not hidden photos or deleted versions never received by this client. Oldest files may be evicted by quota.
- Browse retained images from the existing changes screen, globally or for the selected contact.
- Added migration/text-integrity regression test. Local Gradle build/test attempt again blocked downloading Gradle; CI validation required.
- Required device test: initial contact sync, over 256 contacts, simultaneous photo/bio changes, failed downloads/restart, two accounts, archive opening and quota eviction.
