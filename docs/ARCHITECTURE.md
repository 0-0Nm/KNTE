# Architecture baseline / ADR-001

## Context
The Java reference implementation accumulated valuable behavioral contracts while its build environment constrained language/library choices. Refactoring is judged by user workflows and invariants, not language conversion counts.

## Initial structure
- `core`: pure Kotlin/JVM domain records and explicit repository contract plus a private-file implementation; no Android framework dependencies.
- `app`: Kotlin/Compose UI, lifecycle ViewModel and dispatch ownership. UI does not directly read/write files.
- Parent-controlled Gradle/toolchain and CI. Only two physical modules until additional boundaries justify more.

## Data ownership
This slice owns only preview-app private records under `preview-notes-v1`. No old app data, real Vault, formal roots, shared permissions or imported credentials are touched.
Record revision is a content binding, not authentication. Updates require the exact read revision.
File-format and pending-write failures are visible; no silent fallback or automatic deletion.

## Task/state ownership
Repository methods are blocking IO. ViewModel dispatches off the main thread. UI has explicit loading/saving/error/empty states and preserves drafts on failed save.
SavedState is a bounded convenience for UI recreation, not a durable operation receipt. Process-kill behavior needs device evidence.

## Deliberate exclusions
No universal journal framework, dependency injection framework, Room database, networking or automatic migration is introduced just for scaffolding. Evaluate each when a real vertical flow needs it.
A new internal preview record format does not supersede the legacy user's Markdown/JSONL contracts.

### Android platform root alias
The Activity adapter resolves only the framework-provided `filesDir` canonical root inside its lazy IO operation before appending the controlled preview leaf. Core still rejects arbitrary symbolic-link roots/entries; it does not weaken checks globally for externally selected paths. Canonical framework aliases and Android filesystem durability require device validation.
