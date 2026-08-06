# Split the run manifest from the cost record — development plan

Implements the ADR 0021 §8 amendment of 2026-08-05 (PR #92). Written from the
reviewed ADR; the two design reviews' findings are already folded into the ADR,
so this plan takes them as given.

**Shape.** `.orca/cache/runs/` gains a second file per run. `<id>.json` keeps
run fields and sessions and loses `manifestVersion`, `cost` and `turns`.
`<id>-cost.jsonl` is new: append-only, one JSON object per line, never read by
the shell.

**Grouping into PRs.** Four PRs, in order. Each is independently reviewable and
leaves the tree green.

| PR | Tasks | Theme |
|---|---|---|
| A | T1, T2 | Drop the version gate; tolerant session manifest |
| B | T3, T4 | The cost log: writer, reader, wiring |
| C | T5 | Retention across the pair |
| D | T6 | AGENTS.md carve-out and doc sweep |

PR B depends on A (the manifest shape changes under it). C depends on B (there
is no second file to prune until B lands). D is independent but goes last so it
documents what actually shipped.

---

## PR A — drop the version gate

### T1 — Tolerant, version-free session manifest
Remove `manifestVersion` from `RunManifest` and `RunManifest.SupportedVersion`;
swap the codec from `JsonData.strictCodecConfig` to
`withRequireCollectionFields(false).withTransientEmpty(false)`. Remove
`ManifestReader.readManifest`'s version branch and its `SchemaVersion` /
`schemaVersionCodec` helpers, leaving one decode with a warning on failure.

`workDir`, `pid`, `startedAt`, `outcome` and `sessions` keep no default —
`sessions` specifically so an absent array cannot render the menu's
"(0 session(s))" row. `cost` and `turns` come off `RunManifest` in T3; until
then they stay, so this task compiles on its own.

Rewrite the scaladoc at `RunManifest.scala:139-153` and `:187-194` — both argue
for the gate — and `ManifestReader.scala:26-39`.

*Done when:* a manifest carrying `manifestVersion`, `cost` and `turns` decodes
as session-only with no warning, and one carrying none of them decodes
identically. `ManifestReaderTest`'s two version-gate cases (`:74-102`,
`:104-120`) are gone; the first one's verbatim v2 JSON survives as the
assertion that an old file now reads. `CliTest`'s stderr-separation test
(`:940-980`) needs a new way to provoke a warning — a manifest missing
`workDir` is the natural one, and it doubles as the required-field check.

### T2 — Frozen golden fixtures
Check in a literal manifest as a test resource, never edited afterwards: the
v5 shape, with a populated `sessions` array (the existing v2 fixture has an
empty one, which would not catch a session-field regression). A test decodes it
and asserts every session field. This is the mechanism behind the ADR's
additive-only rule; without it the rule is a comment, since every other fixture
in the repo is edited in lockstep with the schema.

*Done when:* the fixture file is committed under `src/test/resources`, a test
decodes it, and adding a required field to `RunManifest` or `ManifestSession`
makes exactly that test fail. Mutation-check that: add `foo: String`, confirm
the fixture test is the failure, revert.

---

## PR B — the cost log

### T3 — `CostLog` writer and record shape
New file in `orca.runner.manifest`. Three record types behind a `type`
discriminator: `run` (orca version, flow, work dir), `turn`, `finish`
(outcome). One codec, tolerant, unknown `type` skipped on read.

The turn record carries `at`, `agent`, `role`, `stage`, `attempt`, `apiCalls`,
a full `ManifestUsage` (all five axes — this is what keeps `RunManifestTest`'s
"mirrors every `Usage` axis" guard meaningful) and `Option[Cost]`. It also
carries the denormalised session identity — harness, agent, session name — so a
line stands alone when its sibling manifest was never written or was pruned.
`promptTokens` does not survive: it is `usage.inputTokens` verbatim.

Appends open the file per line — no held handle, which would need a close hook
on the actor and would not survive the directory being replaced underneath it.
After a failed append the writer terminates the file with a newline before
appending again, so a short write costs one record rather than running the next
one onto the same line.

Delete `CostAccumulator.scala` in full, `Tally` included, and
`ManifestCostSummary` / `ManifestSubtotal`.

*Done when:* a writer driven with two `TokensUsed` events produces a file whose
lines are, in order, one `run`, two `turn`, and — after `finish` — one
`finish`. A turn's usage axes and resolved cost round-trip. No
`ManifestCostSummary` type remains in the tree.

### T4 — Wire it into `RunManifestWriterState`, with its own gate
`TokensUsed` stops accumulating and appends. The append goes through
`safeWrite`'s guard: that handler is pure in-memory today and cannot throw,
and after this it does IO on every turn — an escape from a `tell` handler
closes the actor's channel and quarantines the writer for the rest of the run,
session writes included.

The cost file gets its own creation gate on the first `TokensUsed`. It cannot
share the session manifest's gate: on the autonomous text path `emitTokens`
fires before `emitSessionCommitted` (`BaseAgent.scala:115-116`), so gating cost
on the session event means buffering turns until it opens — the in-memory turn
log this change exists to remove, discarded entirely for a `quietTextTurn`-only
run. `finish` writes the trailer and must land before the caller proceeds, so
it stays an `ask`.

`RunManifestWriterTest`'s cost cases (`:423-490`, `:492-557`) move to the cost
file. `ManifestRoundTripTest`'s cost assertions (`:79-82`) move to a `runner`
round trip; the shell keeps only its session half. Its `manifestFiles` helper
(`:39-40`) filters `ext == "json"`, which `.jsonl` already excludes — confirm
rather than assume.

*Done when:* a run that emits `TokensUsed` but never `SessionCommitted` writes a
cost file and no session manifest; a run that emits neither writes neither.
`RunManifestWriterTest:366-382` (session-less run writes nothing) still passes,
narrowed to the session half, with a sibling covering the cost half. The
`shell` module names no `orca.runner.manifest` cost type in production or test
code — `PriceList` stays in `RunManifestWriter.start`'s signature and is not in
scope.

---

## PR C — retention across the pair

### T5 — Prune by run id
`pruneOldManifests` groups by run id — strip `.json` or `-cost.jsonl` — keeps
the newest 20 ids, and deletes both files of each older id. A run id with only
one of its two files is one run and prunes the same way; a missing half is
never an error.

The trigger moves out of `write()`: it fires once, on the first write of either
file, sharing one `prunedOnce`. Left where it is, a run that never commits a
session never prunes, which is exactly the run T4 starts recording.

Watch the ordering: prune currently runs after this run's own file exists,
which is what makes `RunManifestWriterTest:282-287` ("the current run's own
manifest must survive pruning") hold at 19 old + 1 current. Firing on a first
cost append instead means this run's session manifest does not exist yet, so 20
old survive and the current one makes 21. Pick one and pin it in the test —
either is defensible, but the test's invariant must match.

*Done when:* 25 pre-seeded runs, some as pairs and some as orphaned halves,
prune to the newest 20 run ids with both files of each deleted run gone and no
orphan left behind. A run that only ever appends cost lines prunes.

---

## PR D — the convention and the docs

### T6 — AGENTS.md carve-out
AGENTS.md's "Versioning (0.x)" section forbids defaults on persisted fields and
back-compat machinery, with one documented exception for
`ProgressLog`/`SessionRecord`. Add the second, worded as narrowly as the ADR:
defaults are permitted on the two `.orca/cache/runs/` shapes and nowhere else,
only on fields added after this change, and the five required manifest fields
keep the no-default rule.

Without this the next reviewer correctly flags every optional field the split
introduces.

*Done when:* AGENTS.md names both exceptions and says what the manifest one does
not cover (renames, retypes, respelled wire vocabulary).

---

## Verification, every task

`sbt scalafmtCheckAll`, then `sbt clean compile test` in the foreground. Zero
warnings. Tests mutation-checked: apply the mutation, confirm exactly the
intended test fails, revert.

## Out of scope, recorded

Per-turn `model`. The ADR records it as enabled by the additive cost file, not
as work: the parallel claude plan-mode removal may remove the question that
wants it. Answering it needs `model` on the turn *and* on the session.
