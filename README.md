# KNTE Next — architecture preview

New Kotlin/Compose workspace; not a port-complete replacement for the Java application.
The preserved reference baseline is KNTE Java candidate 0.18.0.27. No real user data or credentials belong here.

## First vertical slice
App-private notes: create → reload list → open → revision-checked edit → reopen.
This is deliberately **not** a Vault diary, formal data-source migration, or live restore.
Current corrected preview: **知温·预览**, package `com.knte.preview.next`; preserves both old Java app and the first `com.knte.preview` experiment. Uses a dedicated internal testing certificate via GitHub encrypted Secrets, not production signing.

## Build
Pinned JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20, Compose BOM 2025.04.01.
Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`.
CI is manual-only (`workflow_dispatch`), no release, no deployment and no auto-install.
A CI success is host-test/build evidence, not Android device/lifecycle acceptance.

## Status boundaries
- Synthetic JVM storage/state tests only until real reports exist.
- No external directory permissions, INTERNET permission, model provider, microphone, reminders, credentials, authority switching, or legacy data writes.
- Debug APKs are internal previews, not production signing or a release contract.
- No old evidence/archive/cache/build folders are imported.

See `docs/ARCHITECTURE.md` and `docs/MIGRATION.md`.

## First verified preview
Cloud run `42955498690` passed for source `ebc9f6a0ab8096ed9e6e3ba394292860857b3155`; 21 host tests and debug APK build passed. Device acceptance is **not performed**. See `docs/FIRST_PREVIEW_ASSESSMENT.md` for scope, artifact identity and next milestones. Later documentation-only commits do not change that compiled-source identity.

Preview2 responds to actual device findings: system-back/cancel discard confirmation, scrollable edit content with fixed actions, concise messages, readable timestamps, inherited launcher icon. Device acceptance of preview2 requires its own evidence; preview1 activity reopen/cold-start persistence was observed, but ordinary rotation/process-death draft recovery was not proven.
