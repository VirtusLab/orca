# `CostLog.read()` is production code with no production caller

**Aspect**: conciseness  **Severity**: low

## Problem

`CostLog.read()` (`runner/src/main/scala/orca/runner/manifest/CostLog.scala:123-133`) carries
carefully engineered tear-tolerant decoding (whole-file replacing-decoder read to survive a tear
through a multi-byte UTF-8 sequence, per-line skip for torn lines and unknown record kinds) plus
three dedicated tests (`CostLogTest.scala:212-238`). Its only callers are tests — verified by grep:
`CostLog` is `private[manifest]`, referenced in production solely by `RunManifestWriter.scala:121-122`
(which only appends), and AGENTS.md's versioning section states outright "nothing reads a cost
log". The class's own scaladoc calls it an "Append-only reader/writer" — half of that description
serves no in-tree consumer, and every future `Usage`-axis change pays the maintenance of a read
path nobody exercises outside its own tests.

## Proposed solution

Move `read()` to the test side until a production reader exists, so the production class states its
true role (append-only):

1. Delete `read()` from `CostLog`; reword its scaladoc to "append-only writer".
2. Add the equivalent parser as a helper in `CostLogTest` (or `runner`'s testkit if
   `ManifestRoundTripTest`-style shell tests ever want it) — same body, same tear tolerance, since
   the tests pinning the on-disk format still need to decode it.
3. Keep the three tear/unknown-kind tests: they pin the WRITTEN format's recoverability contract
   (what a future reader will rely on), now against the test-side parser.

Net: production surface shrinks by ~15 lines and one contract; no behavior change. When a real
consumer lands (e.g. a shell cost view), the parser moves back with it — the format contract stays
pinned throughout.

Must NOT change: the append path, the `.jsonl` naming (the shell's `ext == "json"` listing filter
depends on it), and the `CostRecord` codec.

## Verification

**Verdict: CONFIRMED-WONTFIX.**

Checked CostLog.scala:106/:123-133 (`private[manifest]`, `read()` body as described); repo-wide grep confirms production references are exactly RunManifestWriter.scala:121-122 (append-only) and a scaladoc mention at RunManifest.scala:50; `read()` is called only from CostLogTest.scala:32/214/227/235. The factual claim is fully right.

Wontfix reason: moving `read()` into the test tree is pure churn — same code, one hop away from the `append` whose tear semantics it mirrors, re-churned again the day a real reader lands — for an ~11-line reduction in an already-`private[manifest]` class; the status quo is cheaper than either move.
