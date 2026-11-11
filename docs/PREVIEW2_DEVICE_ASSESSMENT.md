# Preview2: actual device feedback and second verified preview

## Cloud and identity
- Compiled source `b564f7ba306c8f91e82075c617617358a3111934`.
- Actions run `42958372507`: success, 23 host tests (6 file repository + 16 ViewModel + 1 real-file flow integration), 0 failures/errors/skips.
- Package `com.knte.preview.next`, name **知温·预览**, version `0.1.0-preview2` / code2.
- APK SHA256 `eff7d878f7ecf48ee7db3610266edb5ddf5e490934b6aa133f832eaf9a04c022`.
- Dedicated internal certificate SHA256 `6a48902b0ee900bd59809fd18d683f431940c1ea5ca5b690b303948ff8398664`; downloaded APK and installed PackageManager profile independently match.
- Launcher assets are copied byte-exact from the Java reference and assigned in Manifest. Preview suffix separates this experimental app from older apps. No production release or stable product migration is implied.

## What the first device session changed
Preview1 ran on Vivo V2463A / Android16 and saved/edited/reopened a synthetic record, but exposed a real flaw: verbose technical prompts and a full revision hash pushed edit actions below the viewport. System Back exited an unsaved draft with no confirmation. Host tests had not detected this UI problem.

Preview2 was built in response, not as cosmetic Kotlin conversion: concise UI state, human timestamps, scrollable editor, persistent bottom actions, system-back/cancel confirmation. User approved installing the separate corrected package, preserving preview1 and all records. No uninstall or clear-data operation occurred.

## Actual preview2 observations (ATVSX virtual display only)
1. Fresh start shows 0 records and no fake data or permission prompt observed.
2. Created synthetic KNTE_PREVIEW2_SYNTH with revised-device-body; save/list/open showed exact text and 1 record.
3. System Back with draft showed a discard dialog. Choosing Continue editing preserved the title/body; no automatic write or exit.
4. Changed existing draft to unsaved-change-only, selected Cancel then Discard. Saved list still showed revised-device-body, count1.
5. Cold launch showed saved data intact for this synthetic case.
6. In a separately rebuilt landscape virtual display, editor body can be scrolled into view while Save/Cancel remain accessible. Edited to landscape-edit-saved and saved.
7. Final portrait cold launch showed count1 and landscape-edit-saved; native accessibility tree corroborated current package and exact text.
8. Bounded DropBox query found 0 matching persistent crash entries. This is not a guarantee of no crashes or general stability.

## Scope limits
These are UI/accessibility observations and reopen behavior, not direct private-byte extraction or power-loss durability. Input used accessibility injection; visible soft keyboard behavior is not yet accepted. ATVSX orientation rebuilding creates a new display/task and is not ordinary Android rotation or LMK process recreation. No claim of general process-death draft restoration is made. UI confirmation is not a cryptographic user authentication mechanism.

All objects were synthetic, all UI used ATVSX. Old Java package and real Vault/business data were not opened. No storage/microphone grant, model requests, external uploads, release, uninstall or data clearing.

## Adjusted next work
- Keep this package and dedicated certificate for subsequent internal upgrades; do not generate a new temporary debug signing identity every run.
- Before adding many pages, add normal-configuration/process-recreation and keyboard/back instrumentation evidence or a consented physical-device test.
- Review save-ack-loss/draft replay and pending-file recovery semantics before real-data use.
- Design a read-only legacy Markdown/JSONL bridge with synthetic golden fixtures. No implicit live-source adoption or conversion.
