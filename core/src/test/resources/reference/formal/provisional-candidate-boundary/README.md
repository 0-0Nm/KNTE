# provisional-candidate-boundary fixture (CANDIDATE, not normative)

Identity: `knte.reference.formal.candidate-boundary/1`
Task: 6694137d-1bba-4d80-a65a-a221c7a9919d
Baseline: repo0-0Nm/KNTE main 99b413c41fc32d0d8cac56b140fa27293188c802

This directory is a **PROVISIONAL candidate-compatibility fixture**. It is NOT a golden
file, NOT a normative schema sample and NOT a production format declaration. The four
`golden-*.jsonl` files in the parent directory remain the only golden bytes; nothing in
this directory is referenced by any production source path or contract document.

Purpose: pin down read-side / projection-side boundary behaviour that the existing
`FormalReference*Test` classes do not cover, so that a later contract decision cannot
silently change these behaviours without a failing test.

What is deliberately NOT asserted here (business decisions still open, see
`D/continuation-6694137d-.../core-review/CONTRACT_DELTA.md`):
- whether a known optional field's explicit JSON `null` should mean "same as omitted"
  instead of being rejected (`FORMAL_UNSUPPORTED_OPTIONAL_NULL_CONFLICT`);
- whether `seed` may ever be non-null / persisted (`FORMAL_UNSUPPORTED_SEED_EXAMPLE_CONFLICT`);
- the formal shape of `weight_snapshot` (`FORMAL_UNSUPPORTED_WEIGHT_SNAPSHOT_SCHEMA`);
- the formal serialization of the pitfall recent queue
  (`FORMAL_UNSUPPORTED_PITFALL_RECENT_BEFORE_FIELD`).

These fixtures therefore only assert *current, already-implemented, conservative*
behaviour. They never promote an uncertain semantics into a formal requirement.

## Files

| file | member | expected |
| --- | --- | --- |
| `roundtrip-memo.jsonl` | memo | decodes, and decode->encode is byte-identical (canonical roundtrip) |
| `reject-known-optional-null.jsonl` | memo | every line is rejected with `FORMAL_OPTIONAL_NULL` |
| `reject-picklog-optional-null.jsonl` | pick_log | the line is rejected with `FORMAL_OPTIONAL_NULL` |

`roundtrip-memo.jsonl` contains an unknown top-level `seed:null` plus an unknown nested
`x_meta` object that itself carries keys named `due` and `seed` with null values, and a
mixed array. Unknown subtrees are preserved verbatim; only *known top-level* optional
keys are subject to the optional-null rejection.
