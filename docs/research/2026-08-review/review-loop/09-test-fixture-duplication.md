# Review-loop tests re-implement the same agent-stub scaffolding and helpers in every file

**Aspect**: conciseness  **Severity**: low

## Problem

Roughly 200 removable lines of duplicated test scaffolding in `flow/src/test/scala/orca/review/`.

**(a) Stub agents that `FakeAgent` already covers.** `FakeAgent` (ReviewAndFixTest.scala:59-85) takes `onRun: () => Unit`, fired before each scripted reply (FakeAgentCall, line 55). Yet:
- `GatedReviewer` (ReviewAndFixTest.scala:1203-1231, inline in a test) re-implements the full six-method `Agent` surface plus nested `AgentCall`/`AutonomousAgentCall`/`interactive = ???` scaffolding just to `gate.await(...)` before returning `ReviewResult.empty` — exactly `FakeAgent(label, List(ReviewResult.empty), onRun = () => { val ok = gate.await(2, SECONDS); assert(ok, s"$label gate never opened") })`.
- `RendezvousReviewer` (ReviewAndFixTest.scala:1270-1306) is the same shape with `onRun = rendezvousThen`.
- `NamedTool` (ReviewerSelectorTest.scala:59) exists only to carry a name — `FakeAgent(name)` (its call throws if actually run, same effect as the `???`).

The genuinely custom stubs (`TokenEmittingReviewer` at 91-124, `SeedProbingCoder` at 131-173, `RecordingPicker` at ReviewerSelectorTest.scala:26-56) still each repeat the six `withConfig`/`withSystemPrompt`/`withName`/`withTools`/`autonomous = ???`/`interactive = ???` members.

**(b) The `ReviewIssue` construction helper is copy-pasted in five files**: ReviewAndFixTest.scala:185, FixLoopTest.scala:15, FixOutcomeReconcileTest.scala:11, ReviewFixFlowTest.scala:27, ReviewChangeSetTest.scala:36 (`bug`), plus an inline variant in ReviewerSelectorTest.scala — all building the same `ReviewIssue(Severity..., Confidence..., Title(t), ...)` value. A `ReviewIssue` field change means six edits.

**(c) Nine anonymous `ReviewerSelector` instances repeat the full 7-line `prepare` signature** to wrap a one-line narrowing lambda: ReviewAndFixTest.scala:717, 1542, 1577, 1628, 1657; ReviewChangeSetTest.scala:79, 241; ReviewerSelectorTest.scala:349, 366.

**(d) `FakeAgent`/`FakeAgentCall` live in ReviewAndFixTest.scala** but are used cross-file by ReviewChangeSetTest and ReviewFixFlowTest — shared fixtures in a test file named for one suite.

## Proposed solution

All changes in `flow/src/test/scala/orca/review/ReviewLoopFixture.scala` (the existing fixture file) and the test files; no production code changes, no scenario/assertion changes.

1. Move `FakeAgent` and `FakeAgentCall` (with their docs) into `ReviewLoopFixture.scala`.
2. Delete `GatedReviewer`, `RendezvousReviewer`, and `NamedTool`; replace their instantiations with `FakeAgent` + `onRun` as sketched above (the rendezvous/latch logic moves into the test-local closure, where the munit `fail` is already in scope).
3. Extract an open base for the remaining custom stubs, in the fixture file:
   ```scala
   /** Agent stub base: identity + the with* no-ops every review-loop stub
     * repeats; subclasses supply only `resultAs`.
     */
   private[review] abstract class StubAgent(override val name: String)
       extends Agent[BackendTag.ClaudeCode.type]:
     def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
     def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
     def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
     def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
     def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
   ```
   Derive `TokenEmittingReviewer` (which overrides `withName`/`withRole` — keep those overrides), `SeedProbingCoder`, and `RecordingPicker`'s agent from it.
4. One shared issue helper in the fixture: `def issue(title: String, confidence: Double = 1.0, severity: Severity = Severity.Warning): ReviewIssue`; delete the five per-file copies (the most general signature, ReviewAndFixTest.scala:185, already covers every current use — `bug` and the description-carrying variant included, adding a `description` default if a call site needs it).
5. A selector factory in the fixture: `def selector(f: (List[RosterEntry[?]], List[ReviewBatch]) => List[RosterEntry[?]]): ReviewerSelector`, so call sites read `selector((all, history) => ...)`. Test code is not CC-compiled, so the plain lambda satisfies the `->` result type — the existing inline instances already demonstrate this.

Tests: the suite itself is the change; it must pass unchanged in what it asserts. Must NOT change: any test's scenario, assertion, or name; the production `Agent` trait.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; one solution inaccuracy corrected).

Checked ReviewAndFixTest.scala (FakeAgent 59-85, `onRun` fired pre-reply at line 55; GatedReviewer 1203-1231; RendezvousReviewer 1270-1306; TokenEmittingReviewer 91-124; SeedProbingCoder 131-173; `issue` 185-197; anonymous selectors 717, 1542, 1577, 1628, 1657), ReviewerSelectorTest.scala (RecordingPicker 26-56, NamedTool 58-68, selectors 349, 366), ReviewChangeSetTest.scala (`bug` 36-44, selectors 79, 241), ReviewFixFlowTest.scala:27-35, FixOutcomeReconcileTest.scala:11-19, ReviewLoopFixture.scala. All duplication claims hold; the FakeAgent-equivalence claims for GatedReviewer/RendezvousReviewer/NamedTool hold (NamedTool is never run by selector tests, so `???`-vs-throwing-iterator is unobservable). FakeAgent is used by no test outside `orca.review` (grep).

Solution revision — the selector factory covers eight of the nine anonymous instances, not nine. The ninth, ReviewChangeSetTest.scala:241's recording selector, reads `changedFiles` and performs a prepare-time side effect (`seen.set(changedFiles)`), which the `(all, history)` factory cannot express; keep that one as an explicit anonymous instance. Also: FixOutcomeReconcileTest's local `issue` uses confidence 0.9 where the shared helper defaults to 1.0 — `reconcile` never reads confidence, so either pass `confidence = 0.9` explicitly or accept the default; assertions are unaffected either way.
