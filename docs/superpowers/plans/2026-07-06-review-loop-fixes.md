# Review-and-Fix Loop Fixes (findings 4.1–4.3, + 7.7/8.2 riders) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the review-and-fix loop readable top-down (a class with one tail-recursive loop instead of a closure forest), give reviewers structural slug identity (cost prefix only at the emission edge), and replace the selector's smuggled-capability cache with a two-phase API whose effects happen once at loop start.

**Architecture:** Three refactors of `flow/src/main/scala/orca/review/`, in order: structure (`ReviewFixLoop` class, `fixLoopWithState` inlined), selector API (two-phase `ReviewerSelector` trait — user decision: approved break), identity (bare-slug reviewer names, prefix at emission edge, roster resolution — user decision: approved break). Docs/tracker + a 4-agent review wave close it.

**Tech Stack:** Scala 3 (braceless), Ox (`Flow.mapParUnordered` fan-out stays), sbt.

## Global Constraints

- Zero compile warnings; braceless Scala 3; explicit return types on public members.
- `sbt --client` loops; `scalafmtAll` before every commit; full gate per task: `sbt --client "scalafmtAll; compile; Test/compile; test"`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; never commit `.superpowers/` or `docs/superpowers/`.
- Branch: continue on `session-identity-fixes`.
- `reviewAndFixLoop`'s public signature does NOT change except the `reviewerSelection` parameter's TYPE (the new trait). `fixLoop`'s signature does not change at all.
- Cost attribution is a hard behavioral invariant: `OrcaEvent.TokensUsed.agent` for reviewer turns must still carry the `reviewer: <slug>` form (the CostTracker breakdown groups on it), and lint stays `reviewer: lint`.
- Test suites `ReviewAndFixTest`, `FixLoopTest`, `ReviewFixFlowTest`, `ReviewerSelectorTest`, `AllReviewersTest`, `LintTest` (flow module) are the regression net; they adapt to renames/shapes but their behavioral assertions must survive.
- User preference (standing): no new `synchronized`/atomics — the loop's state threading and `Flow.mapParUnordered` fan-out already comply; keep it that way.

---

### Task 1: `ReviewFixLoop` class — structure only (finding 4.1, rider 7.7)

**Files:**
- Modify: `flow/src/main/scala/orca/review/ReviewLoop.scala`
- Test: existing `flow/src/test/scala/orca/review/{FixLoopTest,ReviewAndFixTest,ReviewFixFlowTest}.scala` (behavior net — zero assertion changes expected)

**Interfaces:**
- `reviewAndFixLoop(...)` — signature byte-identical in this task; body becomes `new ReviewFixLoop(...).run()`.
- Produces: `private[review] class ReviewFixLoop[B <: BackendTag](<the 12 current parameters as constructor params>)(using ctx: FlowContext, ev: InStage)` with `def run(): IgnoredIssues`. The nested defs become private methods: `sampleDiff`, `filterByConfidence`, `reviewWithSession`, `runReviewersAndLint`, `evaluate`, `fix`; `AgentOutcome` becomes a private enum in the class; `taskTitle`/`changedFiles` become vals. The two `require`s move to the class body (fail at construction, same as today).
- `fixLoopWithState` + its `Step` enum are DELETED. `run()` carries the loop directly:

```scala
  def run(): IgnoredIssues =
    orca.display("Review & fix")
    @scala.annotation.tailrec
    def loop(
        accumulated: IgnoredIssues,
        iteration: Int,
        state: ReviewLoopState
    ): IgnoredIssues =
      orca.display(s"Iteration ${iteration + 1}")
      val (result, nextState) = evaluate(state)
      val issues = result.issues
      if issues.isEmpty then
        emitStep("No review comments")
        accumulated
      else if iteration >= maxIterations then
        emitStep(s"Reached max iterations ($maxIterations); bailing out")
        accumulated ++ IgnoredIssues(
          issues.map(i => IgnoredIssue(i.title, s"max iterations ($maxIterations) reached"))
        )
      else
        val outcome = fix(issues)
        emitStep(s"Fixed ${outcome.fixed.size}, ignored ${outcome.ignored.size}")
        if outcome.fixed.isEmpty then accumulated ++ IgnoredIssues(outcome.ignored)
        else loop(accumulated ++ IgnoredIssues(outcome.ignored), iteration + 1, nextState)
    loop(IgnoredIssues(Nil), 0, ReviewLoopState.empty)
```

- `fixLoop` keeps its exact signature and becomes a thin standalone `@tailrec` recursion with the same stop policy (no state threading — it has none). Repeat the policy code; do NOT re-generalize (the `S`-threading driver served two callers, one of which was `Unit` — that is the accidental complexity being removed).
- The loop-policy comments (max-iterations counts FIX attempts so up to N+1 evaluations; display-not-stage) move onto `run()`/`fixLoop`.
- **Rider (finding 7.7):** the `formatCommand` shell-out becomes `os.proc("bash", "-c", cmd).call(check = false, mergeErrIntoOut = true)` and its comment corrected (stderr previously leaked to the terminal, tearing the status row — the claim "output is captured" was true for stdout only). Matches the `lint` sibling.

- [ ] **Step 1:** Restructure per the interface block. This is behavior-identical except the 7.7 one-liner; the three test suites are the net.
- [ ] **Step 2:** Verify full gate; `flow/testOnly orca.review.*` green with zero assertion changes.
- [ ] **Step 3:** Commit — `refactor(review): promote reviewAndFixLoop to a ReviewFixLoop class; inline the state-threading driver; capture formatter stderr`.

---

### Task 2: Two-phase `ReviewerSelector` (finding 4.3)

**Files:**
- Modify: `flow/src/main/scala/orca/review/ReviewerSelector.scala`, `flow/src/main/scala/orca/review/ReviewLoop.scala` (ReviewFixLoop wiring)
- Test: `flow/src/test/scala/orca/review/ReviewerSelectorTest.scala` (+ any selector construction in `ReviewAndFixTest`/`FlowCompilesTest`/examples — grep `agentDriven|allEveryRound|onlyPreviouslyReporting|ReviewerSelector`)

**Interfaces:**
- Produces (replacing the function type):

```scala
/** Picks which reviewers run on each iteration of [[reviewAndFixLoop]].
  *
  * Two-phase: [[prepare]] is called ONCE at loop start with the loop-constant
  * context (roster, task title, changed files) and the loop's own capabilities
  * — any gated effect (e.g. [[ReviewerSelector.agentDriven]]'s picker LLM call)
  * happens there, inside the loop's stage. It returns the pure per-iteration
  * narrowing: given the review history (most recent batch first), which
  * reviewers run this round. Nothing is captured across loops, so selector
  * values are freely reusable.
  */
trait ReviewerSelector:
  def prepare(
      all: List[Agent[?]],
      taskTitle: Title,
      changedFiles: List[String]
  )(using FlowContext, InStage): List[ReviewBatch] => List[Agent[?]]
```

- Built-ins (complete code):

```scala
object ReviewerSelector:

  /** Every reviewer runs every iteration. */
  val allEveryRound: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[Agent[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] => List[Agent[?]] =
      _ => all

  /** First iteration runs every reviewer; later rounds only those that
    * reported last round. (Trade-off note moves over unchanged.)
    */
  val onlyPreviouslyReporting: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[Agent[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] => List[Agent[?]] =
      history =>
        history.headOption match
          case None        => all
          case Some(batch) => batch.reviewersWithIssues

  def agentDriven(
      agent: Agent[?],
      instructions: String = ReviewLoopPrompts.SelectReviewers,
      descriptions: Map[String, String] = ReviewerPrompts.descriptionsByToolName,
      filePatterns: Map[String, Regex] = ReviewerPrompts.filePatternsByToolName
  ): ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[Agent[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using ctx: FlowContext, ev: InStage): List[ReviewBatch] => List[Agent[?]] =
      <the current body: eligible filter → infos → empty-descriptions warning →
       ONE picker LLM call → SelectedReviewers(...).pick(eligible) →
       empty-selection fallback to eligible — verbatim from today's code, minus
       the `var cached` (the single call IS the cache now)>
      val active = <result>
      _ => active
```

Notes: `agentDriven` LOSES its `(using ctx, ev)` — construction is now pure; the capabilities arrive at `prepare`. The "Single-loop scope" scaladoc warning is deleted (the footgun no longer exists — say so in the commit message). The picker still runs before the first review round (prepare is called before iteration 1), same effective timing as today's first-iteration lazy call.
- `ReviewFixLoop`: in `run()` (before the loop) — `val selectRound = reviewerSelection.prepare(reviewers, taskTitle, changedFiles)`; `evaluate` uses `selectRound(state.history)` instead of `reviewerSelection(state.history, reviewers, taskTitle, changedFiles)`.

- [ ] **Step 1: Failing tests.** Rewrite `ReviewerSelectorTest` around the trait: the existing behavioral cases (pick honored, hallucination dropped, empty-pick fallback, file-pattern pre-filter, description warning) call `selector.prepare(...)(...)` then apply the returned function; ADD one new test pinning the new property: `"agentDriven queries the picker exactly once per prepare, across many rounds"` — a counting stub picker agent; call `prepare` once, apply the returned function to 3 different histories; assert 1 LLM call. And `"a selector value is reusable across loops"` — call `prepare` twice (two loops); assert 2 picker calls (fresh pick per loop, no cross-loop cache). RED: trait shape doesn't exist.
- [ ] **Step 2:** Implement; adapt `ReviewFixLoop`, examples (`grep -rn "agentDriven\|ReviewerSelector" examples runner/src/test flow/src/test`), `FlowCompilesTest` if it mirrors a selector.
- [ ] **Step 3:** Verify full gate. **Step 4:** Commit — `feat(review)!: two-phase ReviewerSelector — effects at loop start, pure per-round narrowing`.

---

### Task 3: Bare-slug reviewer identity; prefix at the emission edge (finding 4.2)

**Files:**
- Modify: `flow/src/main/scala/orca/review/Reviewers.scala`, `SelectedReviewers.scala`, `ReviewerSelector.scala` (agentDriven internals), `ReviewLoop.scala` (roster resolution + edge prefix)
- Test: `flow/src/test/scala/orca/review/{AllReviewersTest,ReviewerSelectorTest,ReviewAndFixTest,ReviewTypesTest}.scala` (grep `"reviewer: "` across flow tests for every site)

**Interfaces / changes:**
1. `buildReviewers` stops baking the prefix: `.withName(r.name)` (bare slug). Its scaladoc: names are bare slugs; the loop applies the `reviewer: ` prefix only when labelling the actual LLM run for cost attribution.
2. `ReviewerPrompts`: `stripNamePrefix` DELETED; `descriptionsByToolName`/`filePatternsByToolName` renamed `descriptionsBySlug`/`filePatternsBySlug`, keyed by bare slug (`all.map(r => r.name -> r.description)`). `NamePrefix` stays (emission edge + lint label). Scaladoc on `NamePrefix` updated: it appears ONLY in emitted events, never in identity.
3. `SelectedReviewers.pick` single-form: `all.filter(r => names.contains(r.name))` — the dual-form comment dies.
4. `agentDriven` internals: `ReviewerInfo(name = r.name, ...)` (no strip; the ambiguity-bug comment shrinks to "names are bare slugs; the cost prefix never reaches the picker"), `descriptions.getOrElse(r.name, "")`, `filePatterns.get(r.name)`; the empty-descriptions warning's hint text updates (stale "names lack the `reviewer: ` prefix" wording → "custom reviewers without matching description keys?").
5. `ReviewFixLoop` roster resolution — after `selectRound(state.history)`:

```scala
  /** The selector may return arbitrary agents; only roster members run. Foreign
    * agents (same-named copies from another backend, or agents never in the
    * roster) are dropped with a visible warning — the per-reviewer session map
    * is keyed by roster slug, and its `SessionId.Untyped.as[RB]` recovery is
    * sound only because slug → backend is fixed by the roster (uniqueness is
    * `require`d at construction).
    */
  private def resolveAgainstRoster(selected: List[Agent[?]]): List[Agent[?]] =
    val byName = reviewers.map(r => r.name -> r).toMap
    val (known, foreign) = selected.partition(a =>
      byName.get(a.name).exists(_ eq a) || byName.contains(a.name)
    )
    if foreign.nonEmpty then
      emitStep(
        s"reviewer selection: dropped ${foreign.map(_.name).mkString(", ")} — not in the configured roster"
      )
    known.map(a => byName(a.name))
```

(Resolution maps every selected agent back to the CANONICAL roster instance by slug, so a selector returning renamed/rebuilt copies cannot deliver a wrong-backend session id — the `.as[RB]` hole from the finding.) Update the `reviewWithSession` scaladoc's soundness argument to cite the roster resolution instead of just the `require`.
6. Emission-edge prefix in `ReviewFixLoop`: in `reviewWithSession`, run the LLM via `val labelled = r.withName(s"${ReviewerPrompts.NamePrefix}${r.name}")` and `labelled.resultAs[ReviewResult]...`; session-map key stays the BARE `r.name`. In `runReviewersAndLint`'s tap and `formatReviewerOutcome` calls, display the bare slug (screen output changes from `reviewer: performance: 2 issues` to `performance: 2 issues` — intentional, note in commit message). Lint keeps `agent.withName("reviewer: lint")` (build the label from `NamePrefix`).

- [ ] **Step 1: Failing tests.** `AllReviewersTest`: names are now bare slugs (`performance`, not `reviewer: performance`) — flip the expectations; ADD `"reviewer LLM runs are labelled with the cost prefix"` — drive one reviewer round with a recording events listener and assert the `TokensUsed.agent == "reviewer: <slug>"` (find the existing ReviewAndFixTest harness that records events); ADD `"a selector returning a same-named foreign agent runs the roster instance"` — selector returns a rebuilt agent with a roster slug but different backend stub; assert the roster instance's backend received the call (or simply that the emitted warning fires and the session map keys stay roster-bound — use whichever the harness can observe). RED first.
- [ ] **Step 2:** Implement 1–6; sweep `grep -rn '"reviewer: ' flow examples runner` for remaining prefixed-name assumptions (prompt resources, docs).
- [ ] **Step 3:** Verify full gate. **Step 4:** Commit — `feat(review)!: bare-slug reviewer identity; cost prefix only at the emission edge; roster-resolved selection`.

---

### Task 4: Docs + tracker + review wave

- [ ] **Step 1:** Delete `flow/src/main/scala/orca/review/ReviewContext.scala` (finding 8.2 — zero usages; re-verify with `grep -rn "ReviewContext" --include=*.scala .` before deleting).
- [ ] **Step 2:** ADR 0011 (reviewer roster) — dated amendment (2026-07-06): reviewer identity is the bare slug; the `reviewer: ` prefix is an emission-edge cost label; selection is two-phase (`prepare` once per loop) and roster-resolved. AGENTS.md — grep `reviewer` for stale prefix/selector descriptions and update.
- [ ] **Step 3:** `complexity-review.md`: tick 4.1 (Task 1 commit), 4.2 (Task 3 commit), 4.3 (Task 2 commit), 7.7 (Task 1 commit), 8.2 (this commit). Commit — `docs: reviewer identity/selector amendments; tick findings 4.1-4.3, 7.7, 8.2`.
- [ ] **Step 4: Review wave** over the whole 4.x range: **code-functionality-reviewer** (fable; probe: session continuity across iterations after the renames — same reviewer must resume the same session; the roster-resolution partition logic; mapParUnordered fan-out unchanged), **scala-fp-reviewer** (opus; the two-phase capability flow — no residual captures; purity of the returned per-iteration functions), **simplicity-reviewer** (did the class restructure leave dead members; is resolveAgainstRoster minimal), **test-reviewer** (did the rewrites preserve one-scenario-per-test; are the new pins real).
- [ ] **Step 5:** Triage; ONE batch fixer; re-verify; commit `fix(review): address review findings on the review-loop refactor`; push the branch.
