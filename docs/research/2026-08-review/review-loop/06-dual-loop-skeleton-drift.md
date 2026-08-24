# The two loop skeletons are held together by five helpers and have already drifted in user-visible messaging

**Aspect**: complexity  **Severity**: low

## Problem

`flow/src/main/scala/orca/review/ReviewLoop.scala` writes the fix-loop control skeleton twice: `fixLoop` (lines 140-168) and `ReviewFixLoop.run` (lines 744-796). Five helpers exist expressly to keep them aligned — `stopPolicy` (line 51: "shared by fixLoop and ReviewFixLoop.run"), `DefaultMaxIterations` (line 46: "Named once so the two can't drift apart"), `recordIgnored` (line 96: "Every accumulation point in both loops"), `NoFixesReason`, `capExitMessage`, plus `announceFixTurn`. Despite that, the halt arms have diverged in user-visible behaviour:

```scala
// run(), line 784:
orca.display("Fixer reported no fixes; bailing out")
// fixLoop, lines 160-165: same halt condition, no such message —
// the user only sees announceFixTurn's "Fixed 0, ignored N".
```

Every change to loop semantics (halt condition, exit messaging, and — if finding 01 lands — the fixed-title pruning) must be applied in both places, and the existing divergence shows that doesn't reliably happen.

A full unification (`runLoop[S]` generic over threaded state, declines, and the gate fold) was considered and is NOT proposed: the parameterization it needs outweighs the ~25 duplicated lines, and the `ReviewFixLoop` side is CC-compiled where higher-order plumbing carries extra cost. The cheap fix is unifying the halt arm, where the drift actually occurred.

## Proposed solution

Extract the shared fixer-halt arm next to the other loop helpers (top of ReviewLoop.scala):

```scala
/** The fixer-reported-no-fixes halt, shared by [[fixLoop]] and
  * [[ReviewFixLoop.run]]: announces the bail-out and returns the additions
  * each caller folds via [[recordIgnored]] (the caller may prepend its own,
  * e.g. gate rejects).
  */
private[review] def fixerHaltAdditions(
    outcome: ReconciledFixOutcome
)(using FlowContext): List[List[IgnoredIssue]] =
  orca.display("Fixer reported no fixes; bailing out")
  List(outcome.ignored, outcome.unaccounted.map(IgnoredIssue(_, NoFixesReason)))
```

Call sites:
- `fixLoop` (lines 160-165): `recordIgnored(accumulated, fixerHaltAdditions(outcome)*)` — this *adds* the bail-out message to `fixLoop`, aligning it with `run`.
- `run` (lines 783-790): `recordIgnored(accumulated, (gated.issues :: fixerHaltAdditions(outcome))*)` — gate rejects stay first (the ordering rule from finding 02; if 02's `conclude` lands, `fixerHaltAdditions` is called from its `FixerHalted` arm instead).

`NoFixesReason`'s scaladoc ("Only the halt exits record these") then lives beside its only consumer.

Do NOT unify the clean-exit messages: `fixLoop`'s literal "No review comments" (line 152) vs `run`'s `doneMessage(stillGated.size)` (line 771) differ legitimately — `fixLoop` has no confidence gate.

Tests: update `FixLoopTest` to assert the "Fixer reported no fixes; bailing out" display now appears on `fixLoop`'s halt (it pins the alignment); existing `ReviewAndFixTest` halt tests must pass unchanged.

Must NOT change: halt semantics (halt only when reconciled `fixed` is empty), the recorded reasons, the `run`-side gate-rejects-first ordering, `stopPolicy`.

## Verification

**Verdict: CONFIRMED.**

Checked `fixLoop` 140-168 (halt arm 160-165 has no bail-out display), `run` 744-796 (line 784 displays it), and the cited alignment helpers at lines 46, 51, 91, 96, 114, 79 — comments verbatim as claimed. The rejection of full unification is the right call under the less-code principle (parameterization would cost more than ~25 duplicated lines, especially under CC). Splat typing checks out. The deliberate behaviour change (fixLoop gains the bail-out message) is called out and test-pinned by the finding itself.

Implementation notes: in `run`, call the helper as `fixerHaltAdditions(outcome)(using ctx)` — the explicit-`ctx` pattern the adjacent `announceFixTurn` call uses (line 782), since implicit search would otherwise select `fc: FlowControl` and CC rejects its root capability. Plain `private` suffices (both callers are in this file).

Ordering: implement after finding 02 — with 02's `conclude` in place, `fixerHaltAdditions` is called from the `FixerHalted` arm, and the bail-out display lives in this helper only (see 02's Verification).
