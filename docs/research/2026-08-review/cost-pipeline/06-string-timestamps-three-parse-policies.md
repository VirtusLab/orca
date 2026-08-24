# Timestamps cross the schema as `String` and are re-parsed at three sites with three failure policies

**Aspect**: complexity  **Severity**: medium

## Problem

The writer stamps `clock().toString` into `at`/`firstSeenAt`/`lastActiveAt`/`startedAt`, all typed
`String` (`runner/src/main/scala/orca/runner/manifest/RunManifestWriter.scala:197,275-276,324`;
`RunManifest.scala:57-58`, `ManifestSession` fields). Every consumer re-decides both the parse and
the malformed-value policy:

- `shell/src/main/scala/orca/shell/sessions/ManifestReader.scala:52-57` validates
  `Instant.parse(manifest.startedAt)` and skips the file with a warning — then line 65 parses the
  same string again to sort.
- `shell/src/main/scala/orca/shell/sessions/SessionPicker.scala:102-103` parses `lastActiveAt` with
  a different, silent policy: `Try(Instant.parse(...)).getOrElse(Instant.EPOCH)`.

So a malformed `startedAt` skips the manifest loudly, while a malformed `lastActiveAt` silently
sorts the row to the epoch. That is per-call-site re-defaulting of a wire field's semantics — the
thing AGENTS.md says is decided once, at decode.

## Proposed solution

Type the fields `java.time.Instant` in `RunManifest`, `ManifestSession`, and `CostRecord`
(`at`/timestamps in `CostLog.scala:72-88`). jsoniter-scala derives `Instant` codecs natively and
its ISO-8601 wire form round-trips what `Instant.toString` already writes, so existing manifests
(including the golden fixtures) decode unchanged and the written bytes stay identical.

Fallout, all reductions:
- `RunManifestWriterState`: pass `clock()` values directly, drop the `.toString` calls.
- `ManifestReader.list`: delete the `startedAt`-unparseable branch entirely — a malformed timestamp
  now fails `readManifest`'s existing skip-with-warning path, one policy for the whole file — and
  sort by the field directly.
- `SessionPicker.recency`: becomes `o.session.lastActiveAt` (delete the `Try`/EPOCH fallback and its
  justifying comment; a manifest with a broken timestamp no longer reaches the picker at all).

Tests: `ManifestReaderTest` "unparseable startedAt" keeps its scenario but the warning now comes
from the codec (assert filename still present); `RunManifestGoldenTest` unchanged (that it passes IS
the compat proof); drop nothing else. Update `RunManifestWriterTest` assertions comparing timestamp
strings to compare `Instant`s.

Must NOT change: the on-disk format (ISO-8601 strings, byte-identical for writer-produced values),
the additive-compat rule, and `ManifestReader`'s skip-don't-abort listing behavior.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; missed consumers and a guard added).

Checked the writer's `.toString` stamps (RunManifestWriter.scala:197, :259/:275-276, :323 — cited :324, one-line drift), RunManifest.scala:57-58, ManifestReader.scala:52-57 (skip-with-warning) and :65 (second parse to sort), SessionPicker.scala:102-103 (`Try(...).getOrElse(Instant.EPOCH)` — a genuinely different, silent policy), CostLog.scala:73/:88. Two policies for one wire semantic — confirmed.

Solution revision — additional required steps:
- One more consumer missed: `shell/src/main/scala/orca/shell/cli/Tables.scala:64` copies `session.lastActiveAt` into `SessionRow.lastActiveAt`, a script-facing `--json` `String` field — keep the row field a `String` and render `session.lastActiveAt.toString`.
- `CostRecord.Finish.at` retypes along with `Turn.at`; the writer's `clock().toString` sites drop the `.toString` (including `upsertSession`'s `now`).
- The byte-identity claim rests on jsoniter's `Instant` encoder matching `Instant.toString`'s ISO-8601 form; `RunManifestGoldenTest` passing IS the proof — if it fails on re-encode, stop and report rather than editing the frozen fixture.
- `ManifestReaderTest`'s newest-first test asserts on `startedAt` strings (lines 62-69) — compare `Instant`s.

Ordering: same-files overlap with 05 — implement together or strictly sequentially; also touches ManifestReaderTest (overlaps 11) and CostLog.scala (overlaps 07) — sequence.
