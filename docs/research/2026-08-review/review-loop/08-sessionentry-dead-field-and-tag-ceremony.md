# `SessionEntry.entry` is never read, and the backend-tag pairing it exists for has no consumer

**Aspect**: conciseness  **Severity**: low

## Problem

`flow/src/main/scala/orca/review/ReviewLoop.scala:178-182`:

```scala
private case class SessionEntry[B <: BackendTag](
    entry: RosterEntry[B],
    chat: Chat[B],
    lastDiff: String
)
```

`entry` is written once (`SessionEntry(e, chat, currentDiff)`, line 507) and never read: `resumeReview` uses `se.chat`, `se.lastDiff`, `se.copy`; the sessions map is keyed from `RoundContribution.entry.id` (line 209), not from the stored entry. The scaladoc's first sentence ("paired with its entry under a single backend tag `B`", lines 170-171) documents a pairing the code never exploits — no call site needs the entry's and the chat's tags to agree; `resumeReview[R <: BackendTag]` (line 464) re-opens the existential only to call `se.chat.resultAs[...]`, which does not depend on the tag match. `RosterEntry.wrap`'s doc (ReviewerRoster.scala:37-39) likewise promises "the entry's `agent` and any session paired with it in the loop's state share one `B` by construction" — an invariant with no consumer. Reading cost with no checking payoff.

## Proposed solution

In ReviewLoop.scala:

1. Delete the field: `private case class SessionEntry[B <: BackendTag](chat: Chat[B], lastDiff: String)`; the construction at line 507 becomes `SessionEntry(chat, currentDiff)`.
2. Trim the scaladoc (lines 170-177) to the two facts that remain true: the chat bundles the role-tagged agent with its conversation id so a resume just calls it again, and the `lastDiff` semantics.
3. Try collapsing the now-pointless tag plumbing: if `Chat[?]`'s `resultAs[ReviewResult].autonomous.run(...)` type-checks on the existential (this file is CC-compiled — verify by compiling, per AGENTS.md's "verify empirically" rule), make it `SessionEntry(chat: Chat[?], lastDiff: String)` and drop the `[R <: BackendTag]` type parameters from `resumeReview` (line 464) and the corresponding re-opening in `reviewWithSession`. If the existential doesn't type-check under CC, keep the `[B]` parameter on `SessionEntry` — step 1 alone already removes the dead data and the false pairing claim.
4. Update `RosterEntry.wrap`'s scaladoc (flow/src/main/scala/orca/review/ReviewerRoster.scala:37-39): drop the "any session paired with it" clause; the type-capture match itself stays (it is still how an `Agent[?]` becomes a typed `RosterEntry`).

Out of scope (do NOT do here): removing `RosterEntry`'s own `[B]` parameter — `RosterEntry` is public API used by `ReviewBatch` and every `ReviewerSelector`, and de-parameterizing it is a separate decision with wider blast radius.

Tests: none to add — the whole flow-module test suite compiling and passing (including `CcNegativeCompileTest`) is the verification; no behaviour changes.

Must NOT change: `RosterEntry`'s public shape, `ReviewerId` minting, the sessions-map keying, `lastDiff` semantics.

## Verification

**Verdict: CONFIRMED.**

Checked ReviewLoop.scala:178-182 (verbatim), construction at 507, and every read of `SessionEntry` (469 `se.lastDiff`, 471 `se.chat`, 478 `se.copy`) — `entry` is genuinely never read; the sessions map is keyed from `RoundContribution.entry.id` at 209; ReviewerRoster.scala:37-39's pairing promise verified. `SessionEntry` is file-private, so no external breakage; `RoundContribution.newSession: Option[SessionEntry[?]]` keeps its shape. Step 3's existential collapse correctly mandates empirical CC verification (AGENTS.md) with a stated fallback, and the out-of-scope fence on `RosterEntry`'s `[B]` is right — it is public API (re-exported in runner/exports.scala, used by `ReviewBatch` and every selector).

Ordering: same-struct conflict with finding 05 (retypes `lastDiff` to `LastSent`) — implement in one PR, or 08's deletion first.
