# First-device acceptance (not executed)

Package `com.knte.preview` must coexist with the Java reference package. Installing is a separate authorization, never a CI side effect.

Use synthetic notes only. Do not grant external storage, select a Vault, input credentials or restore real records.

1. Fresh start: clear empty state, no permission prompt, no sample record pretending to be user data.
2. Create a synthetic title/body, save once, reload list, open and compare exact content.
3. Edit, save, reopen; former revision cannot overwrite a later change.
4. Tap save repeatedly: one record/change, not duplicate writes.
5. Rotate with an unsaved bounded draft: draft remains explicitly unsaved.
6. Stop/recreate process: saved data persists; bounded UI draft recovery is checked separately.
7. After normal reopen, verify content without relying on a toast or an in-memory list.
8. Handle corrupt/pending fixture in a separate test profile: visible error, no automatic deletion or truncation.
9. Check keyboard, scroll, large font, back navigation and accessibility labels.
10. Record cold start, memory and rendering observations; do not infer them from Kotlin or Compose adoption.

Results must distinguish screenshot/UI-node observation, independent file/repository evidence and JVM test evidence.
