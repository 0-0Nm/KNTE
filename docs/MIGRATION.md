# Migration plan / staged evidence

0. Freeze reference Java candidate 0.18.0.27 and its source manifest; do not upload old private-data evidence.
1. New standard build + two-module architecture + first app-private note flow.
2. Independently review source and run JVM tests/cloud build. Report compile/build vs Android runtime separately.
3. Explicitly authorize device install in separate preview package and validate create/edit/reopen/rotation/process recreation/failure behavior with synthetic objects.
4. Before any Vault bridge, specify identity, format compatibility, write boundaries, conflicts and permission loss; reuse old golden/fault fixtures selectively.
5. Choose the next high-risk platform flow based on stage-3 results; do not translate all Java classes or repeat old batch counts.

## Current classification
Reusable candidates: parsers, projection algorithms, format contracts, raw-byte safety and failure fixtures.
Redesign candidates: UI event/state ownership, lifecycle/concurrency glue, build provenance and dependency management.
Not copied: credentials, real diaries/audio/accounting, APKs, temporary output, complete session logs and historical evidence trees.

## Acceptance labels
Source written; host tests passed; APK built; device tested; old data compatible are independent claims.
First build is an architecture experiment, not full feature completion and not product release.
