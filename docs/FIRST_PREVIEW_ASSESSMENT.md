# First architecture preview: verified evidence and next plan

## Verified first green build
- Private repository `0-0Nm/KNTE`.
- Compiled source commit `ebc9f6a0ab8096ed9e6e3ba394292860857b3155`.
- GitHub Actions run `42955498690`: success. Manual standard Ubuntu hosted workflow; no release or deployment.
- JUnit XML: 6 real-file core tests, 14 fake-repository ViewModel tests, 1 real-file repository + ViewModel integration test; 21 passed, 0 failures/errors/skips.
- Debug APK: package `com.knte.preview`, version `0.1.0-preview1` / code1; min26/target35.
- APK SHA256 `d4248e4c3dc679a998c91f6c6780be09d95026b43eaa6534cf5e973e1cc7f87f`; cloud artifact digest and embedded source commit independently matched after download.
- Local APK signature verification passed V2. Debug signing is for internal preview, not a stable production signing/update guarantee.

## Architecture result
The first slice now has a real persistence path through the new state/repository boundaries: create, independently reopen, edit with revision checking, reload. CI uses the exact pinned Gradle/Kotlin/Android toolchain. This supports continuing the architecture experiment; it does not establish old-product parity or a final visual design.

The first failed cloud run was retained (SavedStateHandle generic type inference). The successful repair has a different commit and run. Parent corrected Main-thread state ownership and IO dispatch, cancellation propagation, lifecycle collection, title validation and Android framework root normalization before accepting the preview.

## Not verified
No installation, rendered UI, real process death, keyboard/back handling, Android filesystem durability or permission/runtime test has been executed. File-system tests are synthetic host tests. ViewModel tests use a fake repository except the separately named integration test. SavedStateHandle is not a durable save receipt.

Gradle/Android dependencies add a package-local signature permission for non-exported dynamic receiver protection. The app source requests no INTERNET/storage/microphone permission; final merged-manifest review and device acceptance remain explicit gates.

## Adjusted next milestones
1. Preserve this exact baseline; obtain explicit consent for synthetic device installation in the separate preview package.
2. Validate create/edit/reopen, rotation, process recreation, keyboard/back navigation, error presentation and no accidental draft loss using synthetic records.
3. Before relying on repeated CI APK upgrades, decide stable internal signing through a secure channel; ephemeral hosted debug keys are not an update strategy.
4. Add a read-only legacy-format adapter using known synthetic samples, not automatic Vault adoption or live migration.
5. Use device findings to decide whether the next high-risk vertical is external-document access or recording lifecycle. Avoid scaling to all old pages before this feedback.

No full-product progress percentage is inferred from 21 tests or a green build.
