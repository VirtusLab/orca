# One persisted schema, four in-memory spellings of its enums, raw-string matching downstream

**Aspect**: complexity  **Severity**: medium

## Problem

Run outcome is represented three ways, session kind two ways:

- `runner/src/main/scala/orca/runner/manifest/RunManifest.scala:52-72` — `outcome: String`,
  `kind: String`, plus six string constants (`OutcomeRunning`, `KindDurable`, …).
- `runner/src/main/scala/orca/runner/manifest/RunManifestWriter.scala:23-24` — public input enum
  `RunOutcome { Succeeded, Failed }`; lines 140-147 — a second, private
  `enum Outcome(val wireValue: String)` plus the `outcomeOf` mapping; lines 432-439 —
  `enum SessionKind(val wireValue: String)` with `SessionKind.of`.
- Consumers match raw strings: `shell/src/main/scala/orca/shell/sessions/ManifestReader.scala:60`
  (`manifest.outcome == RunManifest.OutcomeRunning`),
  `shell/src/main/scala/orca/shell/sessions/SessionPicker.scala:71`
  (`_.session.kind == RunManifest.KindDurable`).

Five small types/constant sets for two concepts, with hand-written mappings between them. Every
consumer of the persisted type does its own string comparison — the pattern AGENTS.md's
review-derived rules forbid ("Parse protocol strings into enums at the boundary (`Unknown(raw)` for
unrecognized values); match exhaustively downstream"). A new outcome or kind value compiles
everywhere and is silently unmatched in the shell.

## Proposed solution

One enum per concept, string-valued on the wire via a custom jsoniter codec, with an `Unknown(raw)`
case so the manifest's additive-compat rule (a newer build's file read by this one) still holds:

```scala
// in RunManifest.scala
private[orca] enum ManifestOutcome:
  case Running, Succeeded, Failed
  case Unknown(raw: String)   // decode-side tolerance; never written

private[orca] enum ManifestSessionKind:
  case Durable, OneShot, Interactive
  case Unknown(raw: String)
```

Each gets a hand-written `JsonValueCodec` (encode: the known case's wire spelling — `"running"`,
`"succeeded"`, `"failed"`, `"durable"`, `"oneShot"`, `"interactive"`; decode: match, else
`Unknown(raw)`; encoding an `Unknown` is a defect — throw). Then:

- `RunManifest.outcome: ManifestOutcome`, `ManifestSession.kind: ManifestSessionKind`.
- Delete the private `Outcome`, `SessionKind.wireValue`, `outcomeOf`, and the six string constants.
  `finish` keeps the two-case `RunOutcome` input and maps it to `ManifestOutcome` directly.
- `ManifestReader.list` matches `ManifestOutcome.Running` for the crashed check; `SessionPicker`
  partitions on `ManifestSessionKind.Durable`; both become exhaustive matches.

Tests: `RunManifestGoldenTest`'s frozen files must decode unchanged (wire spellings identical — this
is the check that nothing moved on disk); add one decode case for an unknown outcome string mapping
to `Unknown(raw)` rather than failing the file. Update `ManifestReaderTest`/`RunManifestWriterTest`
assertions from strings to enum cases.

Must NOT change: the on-disk spellings, the golden fixtures, `RunOutcome` as `finish`'s narrow input
type, and the additive-only compat guarantee (this change is representation-only).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; missed consumers added).

Checked RunManifest.scala:52-72 (`outcome: String`, `kind: String`, six constants), RunManifestWriter.scala:23-24/:140-147/:432-439, ManifestReader.scala:60, SessionPicker.scala:71 — all exact; repo-wide grep for the constants found the consumers below.

Solution revision — additional required steps:
- Give the known cases a wire-spelling accessor (`def wireName: String`; defect-throw on `Unknown`), needed by three producers beyond the codec: `RunManifestWriterState.finish` stamps the outcome spelling into the cost log's `CostRecord.Finish` (RunManifestWriter.scala:226, today `outcomeOf(outcome).wireValue`; `CostRecord.Finish.outcome` stays a `String` — the spelling should not churn); RunManifestWriter.scala:325 (`state.outcome.wireValue` in `write()`); and `shell/src/main/scala/orca/shell/cli/Tables.scala:61`, where `SessionRow.kind` is a script-facing `--json` `String` field that must keep receiving the wire string.
- Test constants missed by the fallout list: `RunManifestGoldenTest` builds its expected manifests from the constants (lines 41, 51, 63, 86, 96) and `CostLogTest:85` uses `RunManifest.OutcomeFailed` in a `Finish` fixture — switch to enum cases / `wireName`.
- Finding 03 deletes `SessionKind.Interactive` — omit `ManifestSessionKind.Interactive` here; a decoded `"interactive"` then lands on `Unknown(raw)`, which is correct (nothing ever wrote it).

Ordering: after cost-pipeline 03; same-files overlap with 06 (RunManifest.scala, RunManifestWriter.scala, ManifestReader.scala, SessionPicker.scala, Tables.scala) — implement 05 and 06 together or strictly sequentially.
