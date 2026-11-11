# Product alignment and acceptance boundaries

Functional authority: original acceptance criteria/data contract and the non-preview Java implementation,
not the isolated `.note` preview. Visual authority: the existing v3 HTML; settings are independently designed.
The earlier note-screen-only polish plan is not a full-product migration plan.

## Still missing as usable Kotlin product flows
- Formal data-root selection/SAF authority, permission revocation and live-root reading/writing.
- Quiet-room text/archive and actual recording lifecycle.
- Formal diary root integration, templates/filters and robust draft recovery beyond this validation slice.
- Accounting proposals/confirmation/ledger/reversal/reports.
- Time-event materialization/schedule/repeat/system-usage/foreground timing.
- Habits/growth/daily reminder lifecycle.
- Reading worlds/books/chapters/teachers/discussion/exercises/same-source study records.
- Zhiwen real conversations/model configuration/weather/TTS.
- Classification/correction/AI tools/MCP approvals and provenance.
- Reference weights/pick history and cross-view same-source gems.
- Complete settings/themes/diagnostics and data import/export/backup/restore.

Navigation reachability is not functional parity. Available, unconnected, successful-empty and failed reads
must remain distinct. Pure JVM tests, source compilation, APK build, device UI and legacy-data compatibility
are separate acceptance claims. No real data or credentials are used in this slice.

## This source slice
Diary codec and isolated local repository, confirmation plans, before-images, readback and pending refusal;
HTML-inspired product shell with explicit unavailable pages; an isolated diary editor and preserved `.note` tool.
The app entry is being integrated. This is a partial product slice, not whole-product completion.

First improve and verify isolated format safety; then review a user-authorized synthetic SAF bridge.
Never relabel isolated files as a connected Vault or make unimplemented buttons report success.

## Isolated business additions (source; CI/device status reported separately)
Time C-3 events and manual accounting now have app-private validation repositories and UI adapters.
Time supports confirmed start/stop plus pure amend/rename projections; no notifications, overlay timer,
repeat scheduling or formal source authority is claimed. Accounting supports confirmed manual entries,
exact cents, ledger/month/category views; AI parsing and reversal writes remain unavailable.
These additions do not connect real `.knte` or Vault data and do not close all product gaps above.

## Explicit read-only source adapter (preview6 source)
The diary page can request ACTION_OPEN_DOCUMENT_TREE and retain only the granted read permission.
Only direct-child Markdown files are enumerated; original files are never edited or migrated.
Not-selected, revoked permission, successful-empty and provider error are separate states.
A chosen source has no fallback to the isolated validation store. Real-provider behavior and
permission revocation still require device acceptance; fake-provider tests do not establish SAF acceptance.

## Local reading source slice (preview8)
The reading/ask entry hosts a local TXT/Markdown reader: explicit system document selection,
strict bounded UTF-8 import, real chapter index and original text, and explicit private chapter-position save.
Books are not automatically copied or uploaded. This does not implement AI discussion, worlds,
teachers, exercises, annotations or a complete bookshelf. PDF/EPUB and automatic cross-process restore are unsupported.
