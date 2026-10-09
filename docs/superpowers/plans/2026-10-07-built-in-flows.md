# Built-in Flows Reshape Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `quick.sc` / `implement.sc` / `epic.sc` / `resolve.sc` / `review.sc` as the built-in flows, with the generic logic they need in the library.

**Architecture:** Library first (runtime stage-base fix, `orca.plan` roadmap + merged triage, `orca.review.reviewOnce`, `gh.prHandle`), then one task per flow, each updating the shell/runner tests that pin the flow set, then docs.

**Tech Stack:** Scala 3.9, sbt, Ox, jsoniter-scala/tapir (`derives JsonData`), munit, scala-cli flow scripts.

**Spec:** `docs/superpowers/specs/2026-10-07-built-in-flows-design.md`

## Global Constraints

- Every implementer and reviewer invokes the `direct-style-scala` skill (Skill tool) before touching Scala.
- Braceless syntax; explicit return types on public members; zero compiler warnings.
- Comments follow `AGENTS.md` → Code style: only non-obvious facts, present tense, no history, no plan labels.
- Do NOT commit. The user commits; each task ends with the tree compiling and its tests green.
- Run `sbt --client scalafmtAll` after each task (`flows/` is outside scalafmt's scope).
- sbt runs are long: run them as background commands; one implementer at a time.
- On-disk progress-log format may change with no compatibility handling.
- ADRs (except the 0018 amendment in Task 1), `docs/research/`, `docs/plans/`, `docs/superpowers/plans/` (other than this file) are history: do not edit.
- Fix-turn caps in `flows/`: per-epic / per-prompt loops `maxFixTurns = 3` (spelled out), every "Final review" `maxFixTurns = 5`.

## Review Focus

- A run that crashes mid-epic and resumes: the per-epic review must still cover tasks committed before the crash (Task 1 test "re-entered stage keeps its recorded base").
- A resume after a rebase: a recorded base that is no longer an ancestor of HEAD must fall back to HEAD with a `Step`, never diff unrelated history (Task 1 test "recorded base not behind HEAD falls back").
- `resolve.sc` given a PR URL (`https://github.com/o/r/pull/7`): treated as freeform, never as an issue (Task 10 canary + the `issueHandle` code).
- A triage reply with `kind = TestableBug` and a blank `failingTestPath`: decode must fail loudly, not run a reproducer with no path (Task 3 test "TestableBug requires failingTestPath").
- `reviewOnce` whose picker names no real reviewer: must fall back to all eligible reviewers, never review nothing (Task 5 test "empty pick falls back").

---

## File Structure

| File | Responsibility |
| --- | --- |
| `flow/src/main/scala/orca/progress/ProgressLog.scala` | + `StageStart`, `ProgressLog.stageStarts` |
| `flow/src/main/scala/orca/progress/ProgressStore.scala` | + `recordStageStart` |
| `flow/src/main/scala/orca/StageFrames.scala`, `stage.scala` | base commit resolved per stage id; recorded on fresh entry |
| `flow/src/main/scala/orca/plan/Plan.scala` | `epicId` → `id`; `roadmap` ops; drop `assessThenPlan` |
| `flow/src/main/scala/orca/plan/Roadmap.scala` (new) | `Epic`, `Roadmap`, `epicPrompt`, `reviewed` on `WithChat[Roadmap]` |
| `flow/src/main/scala/orca/plan/Triage.scala` | new `Triage` (Reject / Accept + Kind) |
| `flow/src/main/scala/orca/plan/TriageReply.scala` (renamed from `BugTriage.scala`) | flat wire record + `toTriage` |
| `flow/src/main/scala/orca/plan/{AssessedPlan,Verdict}.scala` | deleted |
| `flow/src/main/resources/orca/plan/prompts/{triage,roadmap,roadmap-review}.md` | prompts; `assess-then-plan.md` deleted |
| `flow/src/main/scala/orca/review/ReviewOnce.scala` (new, no capture checking) | `ReviewTarget`, `ReviewerFindings`, `ReviewReport`, `reviewOnce` |
| `flow/src/main/resources/orca/review/prompts/review-once.md` (new) | reviewer prompt moved from `review.sc` |
| `tools/src/main/scala/orca/tools/GitHubTool.scala` | + concrete `prHandle` |
| `flows/{quick,epic,resolve}.sc` (new), `flows/{implement,review}.sc` | the flows |
| `flows/{simple,implement-enhanced,implement-interactive,issue-pr,issue-pr-bugfix}.sc` | deleted |

---

### Task 1: Resume-safe stage base commit

**Files:**
- Modify: `flow/src/main/scala/orca/progress/ProgressLog.scala`
- Modify: `flow/src/main/scala/orca/progress/ProgressStore.scala`
- Modify: `flow/src/main/scala/orca/StageFrames.scala:96-112`
- Modify: `flow/src/main/scala/orca/stage.scala:48-55, 116-135`
- Modify: `flow/src/test/scala/orca/review/ReviewAndFixTest.scala:945,969,998,1023`, `flow/src/test/scala/orca/pr/OpenPrFromBranchTest.scala:111`, `flow/src/test/scala/orca/pr/OpenPrIfGitHubTest.scala:184` (call-site signature only)
- Modify: `adr/0018-stage-bound-flow-runtime.md` (append amendment)
- Test: `flow/src/test/scala/orca/StageRuntimeTest.scala`, `flow/src/test/scala/orca/progress/ProgressStoreTest.scala`

**Interfaces:**
- Produces: `case class StageStart(id: StagePath.Stage, baseCommit: CommitHash) derives JsonData`; `ProgressLog.stageStarts: List[StageStart] = Nil`; `ProgressStore#recordStageStart(start: StageStart)(using WorkspaceWrite): Unit` (keeps the first record per id); `StageFrames#withStage[R](name: String, baseCommit: StagePath.Stage => Option[CommitHash])(f: StagePath.Stage => R): R`.

- [ ] **Step 1: Write the failing tests** in `StageRuntimeTest.scala` (reuse its `reopen`, `commitMessageAgent` helpers):

```scala
  test("a re-entered stage keeps the base it recorded when first entered"):
    val run = TestRun.create(
      new EventDispatcher(Nil),
      lead = Some(commitMessageAgent)
    )
    import run.given
    val atEntry = run.context.git.headCommit()
    val _ = intercept[RuntimeException]:
      stage[String]("outer"):
        val _ = stage("inner"):
          os.write(run.dir / "inner.txt", "1")
          "inner-result"
        throw new RuntimeException("crash after the nested commit")

    val resumed = reopen(run.dir, _ => ())
    import resumed.given
    val base = stage("outer"):
      val _ = stage("inner")("not run: replayed")
      resumed.control.stageBaseCommit
    assertEquals(base, atEntry)

  test("a recorded base no longer behind HEAD falls back to HEAD"):
    val run = TestRun.create(
      new EventDispatcher(Nil),
      lead = Some(commitMessageAgent)
    )
    import run.given
    val id = StagePath.FlowBody.child("outer", 0)
    // A commit HEAD never descended from, as after a rebase.
    val stranger = orca.gitref.CommitHash("0" * 40)
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    run.control.progressStore.recordStageStart(StageStart(id, stranger))
    val base = stage("outer")(run.control.stageBaseCommit)
    assertEquals(base, run.context.git.headCommit())
    assertNotEquals(base, Some(stranger))
```

(If `CommitHash.apply` validates and rejects a zero hash, create a dangling commit with `git commit-tree` on an empty tree and use its hash instead.)

In `ProgressStoreTest.scala`:

```scala
  test("recordStageStart keeps the first base recorded for a stage"):
    val store = freshStore() // the suite's existing store + header setup
    val id = StagePath.FlowBody.child("s", 0)
    store.recordStageStart(StageStart(id, commitA))
    store.recordStageStart(StageStart(id, commitB))
    assertEquals(store.load().map(_.stageStarts), Some(List(StageStart(id, commitA))))
```

Use whatever header/commit fixtures the suite already defines for `freshStore`, `commitA`, `commitB`.

- [ ] **Step 2: Run to verify they fail**

Run: `sbt --client "flow/testOnly orca.StageRuntimeTest orca.progress.ProgressStoreTest"`
Expected: compile failure (`StageStart`, `recordStageStart` not found).

- [ ] **Step 3: Implement**

`ProgressLog.scala` — after `StageEntry`:

```scala
/** The commit a stage started from, recorded when it is first entered, so a
  * re-entry after a crash diffs from the same commit rather than from a HEAD
  * that already holds the stage's own nested commits.
  */
case class StageStart(id: StagePath.Stage, baseCommit: CommitHash)
    derives JsonData
```

and add `stageStarts: List[StageStart] = Nil` as the last `ProgressLog` field (update its scaladoc: "the commit each entered stage started from").

`ProgressStore.scala` — trait:

```scala
  /** Record the commit stage `start.id` started from, unless one is already
    * recorded for it. Requires [[writeHeader]] first; otherwise it throws.
    * Does not commit: the next stage commit carries it.
    */
  def recordStageStart(start: StageStart)(using WorkspaceWrite): Unit
```

`OsProgressStore`:

```scala
  def recordStageStart(start: StageStart)(using ws: WorkspaceWrite): Unit =
    ws.check("progressStore.recordStageStart")
    val log = currentLogOrThrow("recordStageStart")
    if !log.stageStarts.exists(_.id == start.id) then
      writeLog(log.copy(stageStarts = log.stageStarts :+ start))
```

`StageFrames.withStage` — take the base as a function of the path it builds:

```scala
  private[orca] def withStage[R](
      name: String,
      baseCommit: StagePath.Stage => Option[CommitHash]
  )(f: StagePath.Stage => R): R =
    assertOwnerThread("stage(...)")
    val enclosing = frames
    val path = enclosing.head.path.child(name, enclosing.head.next(name))
    frames = new Frame(path = path, baseCommit = baseCommit(path)) :: enclosing
    try f(path)
    finally frames = enclosing
```

Update its scaladoc (`baseCommit` is resolved from the stage's id). Update the six test call sites: `withStage("x", Some(base))` → `withStage("x", _ => Some(base))`, `withStage("x", None)` → `withStage("x", _ => None)`, `withStage("final review", run.context.git.headCommit())` → `withStage("final review", _ => run.context.git.headCommit())`.

`stage.scala` — `inStageFrame`:

```scala
private def inStageFrame[R](name: String)(f: StagePath.Stage => R)(using
    ctx: FlowContext,
    fc: FlowControl
): R =
  // HEAD is read HERE, before the body: once the body's agent starts
  // committing, the commit this stage began from is no longer recoverable.
  fc.withStage(name, id => recordedBase(id).orElse(ctx.git.headCommit()))(f)

/** The base recorded when stage `id` was first entered, if HEAD still descends
  * from it. A rebase or fresh clone can strand it; reviewing from there would
  * diff unrelated history, so the stage starts from HEAD instead and says so.
  */
private def recordedBase(id: StagePath.Stage)(using
    ctx: FlowContext,
    fc: FlowControl
): Option[CommitHash] =
  fc.progressStore
    .load()
    .flatMap(_.stageStarts.find(_.id == id))
    .map(_.baseCommit)
    .filter: base =>
      val usable = ctx.git.isAncestorOfHead(base)
      if !usable then
        ctx.emit(
          OrcaEvent.Step(
            s"stage '${id.display}': its recorded start ${base.short} is no " +
              "longer behind HEAD; reviewing from HEAD instead"
          )
        )
      usable
```

In `runStage`, before the body runs (right after `ctx.emit(OrcaEvent.StageStarted(id))`):

```scala
  fc.stageBaseCommit.foreach: base =>
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    fc.progressStore.recordStageStart(StageStart(id, base))
```

Imports: `orca.progress.StageStart`, `orca.gitref.CommitHash`. If a `FlowControl` test double has no header written (`recordStageStart` throws), make `TestRun.create` write one the same way production does, or guard the call with `fc.progressStore.load().isDefined` — prefer fixing the test double.

Append to `adr/0018-stage-bound-flow-runtime.md` (after the last amendment):

```markdown
> **Amendment (2026-10-07).** A stage's base commit is recorded in the progress
> log (`stageStarts`) when the stage is first entered, and a re-entered stage
> reuses it while HEAD still descends from it. Before this, a stage that crashed
> after its nested stages committed restarted from the later HEAD, so a
> `SampleFromStage` review missed those commits.
```

- [ ] **Step 4: Run to verify they pass, then the whole module**

Run: `sbt --client "flow/testOnly orca.StageRuntimeTest orca.progress.ProgressStoreTest"` → PASS
Run: `sbt --client "flow/test; runner/test"` → PASS

---

### Task 2: Rename `Plan.epicId` to `Plan.id`

**Files:**
- Modify: `flow/src/main/scala/orca/plan/Plan.scala:14-17,39,236,254`
- Modify: `flow/src/main/resources/orca/plan/prompts/review.md:16`
- Modify: `flow/src/test/scala/orca/plan/{PlanTest,PlanGridTest,AssessThenPlanTest}.scala`, `runner/src/test/scala/orca/runner/OpencodeFlowTest.scala:23`
- Modify: `docs/api/data-structures.md:15-16`, `docs/authoring/planning.md:29,36`

**Interfaces:**
- Produces: `case class Plan(id: String, description: String, tasks: List[Task], brief: String)`.

- [ ] **Step 1:** Rename the field and every reference (`grep -rn epicId flow runner docs/api docs/authoring flows`). Scaladoc: "`id` is a kebab-case identifier for the plan itself (it heads the markdown render), NOT the git branch name". `review.md`: "Keep the same id unless it is clearly wrong."
- [ ] **Step 2:** Run `sbt --client "flow/test; runner/test"` → PASS (the `PlanTest` render tests cover the header).

---

### Task 3: One triage (`Reject` / `Accept`), drop assess-then-plan

**Files:**
- Modify: `flow/src/main/scala/orca/plan/Triage.scala` (rewrite)
- Rename+rewrite: `flow/src/main/scala/orca/plan/BugTriage.scala` → `TriageReply.scala`
- Delete: `flow/src/main/scala/orca/plan/{AssessedPlan,Verdict}.scala`, `flow/src/main/resources/orca/plan/prompts/assess-then-plan.md`, `flow/src/test/scala/orca/plan/{AssessThenPlanTest,BugTriageTest}.scala`
- Modify: `flow/src/main/scala/orca/plan/Plan.scala` (remove both `assessThenPlan`; grid scaladoc), `PlanPrompts.scala` (remove `AssessThenPlan`; `Triage` doc), `flow/src/main/resources/orca/plan/prompts/triage.md` (rewrite), `BugReportMatch.scala` (doc), `flow/src/test/scala/orca/plan/{CannedResult,PlanGridTest}.scala`
- Modify: `runner/src/main/scala/orca/exports.scala:47` (drop `Verdict`)
- Modify: `runner/src/test/scala/flowtests/FlowCompilesTest.scala:356-407` (grid canary)
- Test: `flow/src/test/scala/orca/plan/TriageReplyTest.scala` (new)

**Interfaces:**
- Produces:

```scala
enum Triage derives JsonData:
  case Reject(reply: String)
  case Accept(summary: String, brief: String, kind: Triage.Kind)
object Triage:
  enum Kind derives JsonData:
    case TestableBug(failingTestPath: String)
    case UntestableBug(reproductionSteps: String)
    case Change
```

`Plan.{autonomous,interactive}.triage(request: String, agent: Agent[?], instructions: String = PlanPrompts.Triage)(using FlowContext, InStage): WithChat[Triage]` (signature unchanged).

- [ ] **Step 1: Write the failing test** `TriageReplyTest.scala`:

```scala
package orca.plan

class TriageReplyTest extends munit.FunSuite:
  import TriageReply.Kind.{Reject, TestableBug, UntestableBug, Change}

  private val blank = TriageReply(
    kind = Reject,
    reply = "",
    summary = "",
    brief = "",
    failingTestPath = None,
    reproductionSteps = ""
  )

  test("Reject requires a reply"):
    assertEquals(
      blank.copy(reply = "duplicate of #4").toTriage,
      Right(Triage.Reject("duplicate of #4"))
    )
    assert(blank.copy(reply = "  ").toTriage.isLeft)

  test("TestableBug requires summary, brief and failingTestPath"):
    val ok = blank.copy(
      kind = TestableBug,
      summary = "Foo overflows",
      brief = "see Foo.scala",
      failingTestPath = Some("src/test/FooTest.scala")
    )
    assertEquals(
      ok.toTriage,
      Right(
        Triage.Accept(
          "Foo overflows",
          "see Foo.scala",
          Triage.Kind.TestableBug("src/test/FooTest.scala")
        )
      )
    )
    assert(ok.copy(failingTestPath = None).toTriage.isLeft)
    assert(ok.copy(failingTestPath = Some(" ")).toTriage.isLeft)
    assert(ok.copy(summary = " ").toTriage.isLeft)
    assert(ok.copy(brief = " ").toTriage.isLeft)

  test("UntestableBug requires reproductionSteps"):
    val ok = blank.copy(
      kind = UntestableBug,
      summary = "UI freezes",
      brief = "b",
      reproductionSteps = "1. open 2. click"
    )
    assertEquals(
      ok.toTriage,
      Right(
        Triage.Accept("UI freezes", "b", Triage.Kind.UntestableBug("1. open 2. click"))
      )
    )
    assert(ok.copy(reproductionSteps = " ").toTriage.isLeft)

  test("Change needs only summary and brief"):
    assertEquals(
      blank.copy(kind = Change, summary = "Add X", brief = "b").toTriage,
      Right(Triage.Accept("Add X", "b", Triage.Kind.Change))
    )

  test("Announce[TriageReply] is None for a malformed payload"):
    assertEquals(summon[orca.agents.Announce[TriageReply]].message(blank), None)
```

Update `PlanGridTest`'s triage test to send a `TriageReply(kind = TestableBug, …)` and expect `Triage.Accept(…, Triage.Kind.TestableBug(…))`; remove its `AssessThenPlanTest` / `BugTriageTest` scaladoc references (cite `TriageReplyTest`). `CannedResult` doc: "pass a `Plan`, `Roadmap` or `TriageReply`".

- [ ] **Step 2:** Run `sbt --client "flow/testOnly orca.plan.*"` → compile failure.

- [ ] **Step 3: Implement**

`Triage.scala`:

```scala
package orca.plan

import orca.agents.{Announce, JsonData}

/** Outcome of triaging a request — a bug report, a feature request, or any
  * other ask — against the codebase. `Reject` carries the reply to send back
  * to whoever asked. `Accept` means the work should be done: `brief` is what
  * triage verified (files involved, root cause when known), for seeding the
  * planner, and `kind` says what has to happen before planning.
  */
enum Triage derives JsonData:
  case Reject(reply: String)
  case Accept(summary: String, brief: String, kind: Triage.Kind)

object Triage:
  enum Kind derives JsonData:
    /** A defect a focused test can show; the test goes at `failingTestPath`. */
    case TestableBug(failingTestPath: String)

    /** A defect no focused test can show (UI-only, races, environment). */
    case UntestableBug(reproductionSteps: String)

    /** Not a defect: a feature or other change. */
    case Change

  given Announce[Triage] = Announce.from:
    case Reject(_) => "Triage: rejected"
    case Accept(summary, _, Kind.TestableBug(path)) =>
      s"Triage: bug — $summary; failing test at $path"
    case Accept(summary, _, Kind.UntestableBug(_)) =>
      s"Triage: bug — $summary; no automated reproduction"
    case Accept(summary, _, Kind.Change) => s"Triage: change — $summary"
```

`TriageReply.scala` (replaces `BugTriage.scala`):

```scala
package orca.plan

import orca.agents.{Announce, JsonData, schemaFromJsonData, codecFromJsonData}

/** Wire shape of a triage turn: a flat record whose `kind` names the outcome,
  * plus per-outcome fields. Flat rather than a discriminated union so the
  * structured-output schema stays small for the model; [[toTriage]] checks the
  * field combination.
  *
  *   - `Reject` → `reply`.
  *   - `TestableBug` → `summary`, `brief`, `failingTestPath`.
  *   - `UntestableBug` → `summary`, `brief`, `reproductionSteps`.
  *   - `Change` → `summary`, `brief`.
  */
private[plan] case class TriageReply(
    kind: TriageReply.Kind,
    reply: String,
    summary: String,
    brief: String,
    failingTestPath: Option[String],
    reproductionSteps: String
) derives JsonData:

  def toTriage: Either[String, Triage] =
    def need(field: String, value: String): Either[String, String] =
      Either.cond(value.trim.nonEmpty, value, s"triage: $field is empty")
    def accept(kind: Triage.Kind): Either[String, Triage] =
      for
        s <- need("summary", summary)
        b <- need("brief", brief)
      yield Triage.Accept(s, b, kind)
    kind match
      case TriageReply.Kind.Reject => need("reply", reply).map(Triage.Reject(_))
      case TriageReply.Kind.TestableBug =>
        failingTestPath
          .filter(_.trim.nonEmpty)
          .toRight("triage: failingTestPath is missing")
          .flatMap(p => accept(Triage.Kind.TestableBug(p)))
      case TriageReply.Kind.UntestableBug =>
        need("reproductionSteps", reproductionSteps)
          .flatMap(r => accept(Triage.Kind.UntestableBug(r)))
      case TriageReply.Kind.Change => accept(Triage.Kind.Change)

private[plan] object TriageReply:
  enum Kind derives JsonData:
    case Reject, TestableBug, UntestableBug, Change

  /** Defers to [[Triage]]'s own `Announce`; a malformed payload announces
    * nothing, and `Plan.*.triage` throws the structured error at the call site.
    */
  given Announce[TriageReply] = Announce.fromOption: r =>
    r.toTriage.toOption.flatMap(t => summon[Announce[Triage]].message(t))
```

`Plan.scala`: delete both `assessThenPlan` methods; `triage` uses `autonomousResult[TriageReply, Triage](…)(r => getOrFail(r.toTriage))` / `interactiveResult[…]`; the grid scaladoc lists operations `from`, `roadmap` (Task 4 adds it), `triage`. `getOrFail` doc: "a `toTriage` `Left`".

`triage.md` (full replacement):

```markdown
Triage the request above against this repository, then return a structured
verdict. Be skeptical: verify every claim you can against the code — read the
files involved, search for the behaviour described, check whether it already
works as asked.

Look for: missing reproduction steps or ambiguous requirements; claims that
don't match the code (wrong paths, behaviour already implemented); duplicates of
existing work; scope problems (breaking changes, an intentionally out-of-scope
area, a stated design constraint).

Set `kind` to one of:

- `"Reject"` — the request should not be acted on as written. Set `reply`: the
  text posted back verbatim to whoever asked, written directly to them. Ask a
  focused question when a key detail is missing, raise concerns constructively
  when the framing has gaps, or decline politely with evidence when it doesn't
  hold up.
- `"TestableBug"` — a real defect a focused automated test can show. Set
  `failingTestPath` (follow the project's test layout and framework).
- `"UntestableBug"` — a real defect no focused test can show (UI-only, races,
  environment-specific). Set `reproductionSteps`.
- `"Change"` — not a defect: a feature or other change worth making.

For every kind except `"Reject"`, set `summary` (one line, usable as a PR
title) and `brief`: what you verified — the files and functions involved with
paths, the root cause when you found it, and anything non-obvious. The planner
starts from your brief, not from your exploration.

Leave fields that don't apply to your kind empty. Do NOT edit files or run
mutating commands during this turn.
```

`PlanPrompts.Triage` doc: "Used by `Plan.{autonomous,interactive}.triage`: assess the request, then pick `Reject` / `TestableBug` / `UntestableBug` / `Change`." Remove `AssessThenPlan`.

`BugReportMatch.scala` doc: "The agent's verdict on whether a failing test's output reproduces the original report. Used after the reproduction test runs, before any fix is planned."

`exports.scala:47`: `export orca.plan.{BugReportMatch, Plan, Task, Triage, WithChat}`.

`FlowCompilesTest.planningGridSurface`: delete the `assessThenPlan` block; replace the triage match with:

```scala
        triage match
          case Triage.Reject(_)                                  => ()
          case Triage.Accept(_, _, Triage.Kind.TestableBug(_))   => ()
          case Triage.Accept(_, _, Triage.Kind.UntestableBug(_)) => ()
          case Triage.Accept(_, _, Triage.Kind.Change)           => ()
```

Fix the scaladoc above it (`Plan` / `Roadmap` / `Triage`). The `issue-pr.sc` / `issue-pr-bugfix.sc` canaries (`:574-710`) use the removed API: delete them here; Task 10 adds the `resolve.sc` canary.

- [ ] **Step 4:** Run `sbt --client "flow/testOnly orca.plan.*"` → PASS; then `sbt --client "flow/test; runner/test"` → PASS.

---

### Task 4: Roadmap of epics

**Files:**
- Create: `flow/src/main/scala/orca/plan/Roadmap.scala`
- Create: `flow/src/main/resources/orca/plan/prompts/roadmap.md`, `roadmap-review.md`
- Modify: `flow/src/main/scala/orca/plan/Plan.scala` (add `roadmap` to both modes; make `autonomousResult` / `interactiveResult` `private[plan]`)
- Modify: `flow/src/main/scala/orca/plan/PlanPrompts.scala` (+ `Roadmap`, `RoadmapReview`)
- Modify: `runner/src/main/scala/orca/exports.scala:47` (+ `Epic`, `Roadmap`)
- Modify: `runner/src/test/scala/flowtests/FlowCompilesTest.scala` (grid canary)
- Test: `flow/src/test/scala/orca/plan/RoadmapTest.scala` (new), `PlanGridTest.scala`

**Interfaces:**
- Consumes: `Title`, `WithChat`, `PlanPrompts`, `Plan.autonomousResult`/`interactiveResult` (Task 3 state).
- Produces:

```scala
case class Epic(title: Title, goal: String) derives JsonData
case class Roadmap(description: String, epics: List[Epic], brief: String) derives JsonData:
  def epicPrompt(epic: Epic): String
object Roadmap:
  extension (planned: WithChat[Roadmap])
    def reviewed(instructions: String = PlanPrompts.RoadmapReview, variant: Agent[?] => Agent[?] = identity)(using FlowContext, InStage): WithChat[Roadmap]
// in Plan.autonomous and Plan.interactive:
def roadmap(userPrompt: String, agent: Agent[?], instructions: String = PlanPrompts.Roadmap)(using FlowContext, InStage): WithChat[Roadmap]
```

- [ ] **Step 1: Write the failing tests** `RoadmapTest.scala`:

```scala
package orca.plan

import orca.events.EventDispatcher

class RoadmapTest extends munit.FunSuite:
  private given orca.FlowContext =
    new orca.TestFlowContext(new EventDispatcher(Nil))
  private given orca.InStage = orca.InStage.unsafe

  private val storage = Epic(Title("Storage"), "Persist users")
  private val login = Epic(Title("Login"), "Password login")
  private val roadmap =
    Roadmap("User accounts", List(storage, login), "the brief")

  test("epicPrompt names the epics already done and this epic's goal"):
    val prompt = roadmap.epicPrompt(login)
    assert(prompt.contains("User accounts"), prompt)
    assert(prompt.contains("Password login"), prompt)
    assert(prompt.contains("- Storage"), prompt)

  test("the first epic's prompt lists no completed epics"):
    assert(!roadmap.epicPrompt(storage).contains("- Storage"))

  test("autonomous.roadmap pairs the roadmap with the planning chat"):
    val canned = new CannedResult(roadmap)
    val result = Plan.autonomous.roadmap("prompt", canned.agent)
    assertEquals(result.value, roadmap)
    assertEquals(Some(result.chat.id.value), canned.lastSession)
    assertEquals(canned.lastToolSet, Some(orca.agents.ToolSet.NetworkOnly))

  test("reviewed continues the roadmap chat read-only"):
    val improved = roadmap.copy(description = "tighter")
    val reply = new CannedResult(improved)
    val chat = reply.agent.chat()
    val _ = chat.resultAs[Roadmap].autonomous.run("plan")
    val result = WithChat(chat, roadmap).reviewed()
    assertEquals(result.value, improved)
    assert(result.chat eq chat)
    assertEquals(reply.lastToolSet, Some(orca.agents.ToolSet.ReadOnly))
```

- [ ] **Step 2:** Run `sbt --client "flow/testOnly orca.plan.RoadmapTest"` → compile failure.

- [ ] **Step 3: Implement** `Roadmap.scala`:

```scala
package orca.plan

import orca.{FlowContext, InStage}
import orca.agents.{Agent, Announce, JsonData, given}

import scala.annotation.unused

/** One part of a [[Roadmap]]: planned into tasks only when its turn comes. */
case class Epic(title: Title, goal: String) derives JsonData

/** A change too large for one [[Plan]], split into ordered epics. `brief` is a
  * codebase briefing shared by every epic's planner and implementers.
  */
case class Roadmap(description: String, epics: List[Epic], brief: String)
    derives JsonData:

  /** The planning input for `epic`: the whole roadmap's goal, the epics already
    * implemented, and this epic's goal. Earlier epics' code is in the
    * repository by then, so the planner is told to read it rather than assume.
    */
  def epicPrompt(epic: Epic): String =
    val done = epics.takeWhile(_ != epic)
    val doneBlock =
      if done.isEmpty then "No epics are implemented yet."
      else
        "Already implemented, in this order:\n" +
          done.map(e => s"- ${e.title}").mkString("\n")
    s"""Overall change: $description
       |
       |$doneBlock
       |
       |Plan only this epic: ${epic.title}
       |${epic.goal}
       |
       |Read the current code first: earlier epics changed it.""".stripMargin

object Roadmap:
  given Announce[Roadmap] = Announce.from: roadmap =>
    if roadmap.epics.isEmpty then ""
    else
      val plural = if roadmap.epics.size == 1 then "" else "s"
      s"Planned ${roadmap.epics.size} epic$plural:\n" +
        roadmap.epics.map(e => s"  - ${e.title}").mkString("\n")

  extension (planned: WithChat[Roadmap])
    /** Resume the roadmap chat for a critical self-review, returning the
      * improved roadmap on the same chat. The review turn runs on `variant` of
      * the read-only chat agent.
      */
    def reviewed(
        instructions: String = PlanPrompts.RoadmapReview,
        variant: Agent[?] => Agent[?] = identity
    )(using @unused ctx: FlowContext, ev: InStage): WithChat[Roadmap] =
      val improved = planned.chat
        .withAgent(agent => variant(agent.withReadOnly))
        .resultAs[Roadmap]
        .autonomous
        .run(s"$instructions\n\n${render(planned.value)}")
      WithChat(planned.chat, improved)

  /** Markdown for the self-review prompt; never parsed back. */
  private def render(roadmap: Roadmap): String =
    val epics = roadmap.epics
      .map(e => s"\n## Epic: ${e.title}\n\n${e.goal.stripLineEnd}\n")
      .mkString
    s"# Roadmap\n\n${roadmap.description.stripLineEnd}\n$epics" +
      s"\n## Brief\n\n${roadmap.brief.stripLineEnd}\n"
```

`Plan.scala`, in `object autonomous`:

```scala
    /** Split `userPrompt` into a [[Roadmap]] of epics, each planned into tasks
      * later with [[from]] and [[Roadmap.epicPrompt]].
      */
    def roadmap(
        userPrompt: String,
        agent: Agent[?],
        instructions: String = PlanPrompts.Roadmap
    )(using FlowContext, InStage): WithChat[Roadmap] =
      autonomousResult[Roadmap, Roadmap](agent, userPrompt, instructions)(
        identity
      )
```

and the same in `object interactive` via `interactiveResult`. Add `roadmap` to the grid scaladoc.

`PlanPrompts.scala`:

```scala
  /** Used by `Plan.{autonomous,interactive}.roadmap`: split the request into
    * ordered epics, with a shared brief.
    */
  val Roadmap: String = PromptResource.load("/orca/plan/prompts/roadmap.md")

  /** Used by `WithChat[Roadmap].reviewed`; the roadmap is appended after it. */
  val RoadmapReview: String =
    PromptResource.load("/orca/plan/prompts/roadmap-review.md")
```

`roadmap.md`:

```markdown
Your job in this turn is to split the request above into epics — an outline
only. Do NOT edit files, write code, or run build/test commands.

Each epic is a coherent part of the change that leaves the project working when
it is done, and later epics may build on earlier ones. Order them so each one
depends only on epics before it. Give each a short `title` and a `goal`: what
it achieves and where its boundary lies, in a few sentences. Do not list tasks:
each epic is planned into tasks just before it is implemented.

The `description` is 1-3 paragraphs on what the whole change achieves and why.

Fill `brief` with a codebase briefing every epic's planner and implementers
will rely on, since they start from a fresh context: the modules and files
involved with paths, the key types and APIs, the conventions to follow, and
anything non-obvious you learned. Do not restate the epics in the brief.
```

`roadmap-review.md`:

```markdown
Review the roadmap below — which you produced earlier in this session — and
return an improved version of it.

Check: every part of the request is covered by some epic; epics are ordered so
each depends only on earlier ones; each epic leaves the project working; no
epic is so small it should merge with a neighbour, or so large it should split;
goals state their boundaries clearly. Refine the brief in the same spirit.

Do NOT edit files or run mutating commands. Return the complete improved
roadmap, not just the changes.
```

`exports.scala:47`: `export orca.plan.{BugReportMatch, Epic, Plan, Roadmap, Task, Triage, WithChat}`.

`FlowCompilesTest.planningGridSurface`: add

```scala
        // --- roadmap → WithChat[Roadmap], both modes, plus its review ---
        val autoRoadmap: WithChat[Roadmap] =
          Plan.autonomous.roadmap(userPrompt, claude.opus).reviewed()
        val intRoadmap: WithChat[Roadmap] =
          Plan.interactive.roadmap(userPrompt, claude)
        val _ = (autoRoadmap.value.epicPrompt, intRoadmap.value.epics)
```

(Adjust `epicPrompt` usage to a call with an `Epic` from `autoRoadmap.value.epics.head` so it typechecks.)

Add to `docs/authoring/planning.md` a "Roadmaps" section (Task 11 finishes the page): operations list gains `roadmap(userPrompt, agent, instructions?)` returning a `Roadmap`.

- [ ] **Step 4:** Run `sbt --client "flow/testOnly orca.plan.*; runner/test"` → PASS.

---

### Task 5: `reviewOnce` — review without fixing

**Files:**
- Create: `flow/src/main/scala/orca/review/ReviewOnce.scala` (no capture-checking imports)
- Create: `flow/src/main/resources/orca/review/prompts/review-once.md`
- Modify: `flow/src/main/scala/orca/review/ReviewLoopPrompts.scala` (+ `ReviewOnce`)
- Modify: `runner/src/main/scala/orca/exports.scala` (`export orca.review.{…}` gains `ReviewTarget`, `ReviewerFindings`, `ReviewReport`, `reviewOnce`)
- Test: `flow/src/test/scala/orca/review/ReviewOnceTest.scala` (new)

**Interfaces:**
- Consumes: `ReviewerAgent`, `RosterEntry.roster`, `ReviewerSelector`, `ReviewResult`, `ReviewFinding`, `Location`, `MaxConcurrentReviewTasks`, `Par.mapUnordered`.
- Produces:

```scala
case class ReviewTarget(summary: String, diffPath: String, changedFiles: List[String]) derives JsonData
case class ReviewerFindings(reviewer: String, findings: List[ReviewFinding]) derives JsonData
case class ReviewReport(target: ReviewTarget, byReviewer: List[ReviewerFindings]) derives JsonData:
  def render: String
def reviewOnce(reviewers: List[ReviewerAgent[?]], target: ReviewTarget, selection: ReviewerSelector = ReviewerSelector.agentDriven)(using FlowContext, InStage): ReviewReport
```

- [ ] **Step 1: Write the failing tests** `ReviewOnceTest.scala` (uses `ReviewLoopFixture`'s `FakeAgent`, `asReviewer`, `finding`, `Reply`):

```scala
package orca.review

import orca.{FlowContext, TestFlowContext}
import orca.events.EventDispatcher
import orca.plan.Title

class ReviewOnceTest extends munit.FunSuite:
  private given FlowContext = new TestFlowContext(new EventDispatcher(Nil))
  private given orca.InStage = orca.InStage.unsafe

  private val target =
    ReviewTarget("PR o/r#1: add x", ".orca/review.diff", List("a.scala"))

  test("every selected reviewer's findings come back attributed to it"):
    val a = new FakeAgent("alpha", List(ReviewResult(List(finding("A1")))))
    val b = new FakeAgent("beta", List(ReviewResult(Nil)))
    val report = reviewOnce(
      List(asReviewer(a), asReviewer(b)),
      target,
      ReviewerSelector.allEveryRound
    )
    assertEquals(
      report.byReviewer.sortBy(_.reviewer),
      List(
        ReviewerFindings("alpha", List(finding("A1"))),
        ReviewerFindings("beta", Nil)
      )
    )

  test("a reviewer is told where the diff is and what is under review"):
    val a = new FakeAgent("alpha", List(ReviewResult(Nil)))
    val _ = reviewOnce(List(asReviewer(a)), target, ReviewerSelector.allEveryRound)
    val prompt = a.seenPrompts.head
    assert(prompt.contains(".orca/review.diff"), prompt)
    assert(prompt.contains("PR o/r#1: add x"), prompt)

  test("a reviewer whose files pattern matches nothing is not run"):
    val scala = new FakeAgent("scala", List(ReviewResult(Nil)))
    val docs = new FakeAgent("docs")
    val report = reviewOnce(
      List(asReviewer(scala), asReviewer(docs, filePattern = Some("\\.md$".r))),
      target,
      ReviewerSelector.agentDriven(new FakeAgent("picker", List(SelectedReviewers(List("scala")))).agent)
    )
    assertEquals(report.byReviewer.map(_.reviewer), List("scala"))
    assertEquals(docs.seenPrompts, Nil)

  test("an empty pick falls back to all eligible reviewers"):
    val a = new FakeAgent("alpha", List(ReviewResult(Nil)))
    val b = new FakeAgent("beta", List(ReviewResult(Nil)))
    val report = reviewOnce(
      List(asReviewer(a), asReviewer(b)),
      target,
      ReviewerSelector.agentDriven(new FakeAgent("picker", List(SelectedReviewers(List("nobody")))).agent)
    )
    assertEquals(report.byReviewer.map(_.reviewer).sorted, List("alpha", "beta"))

  test("render lists each finding with its reviewer, location and suggestion"):
    val f = finding("Null deref").copy(
      location = Some(Location("a.scala", Some(3))),
      suggestion = Some("check for null")
    )
    val text = ReviewReport(target, List(ReviewerFindings("alpha", List(f)))).render
    assert(text.contains("## Review: PR o/r#1: add x"), text)
    assert(text.contains("1 finding(s) from 1 reviewer(s)"), text)
    assert(text.contains("- **Null deref** (alpha) — `a.scala:3`"), text)
    assert(text.contains("  - suggestion: check for null"), text)

  test("render says so when nothing was found"):
    val text = ReviewReport(target, List(ReviewerFindings("alpha", Nil))).render
    assert(text.endsWith("No findings reported."), text)
```

- [ ] **Step 2:** Run `sbt --client "flow/testOnly orca.review.ReviewOnceTest"` → compile failure.

- [ ] **Step 3: Implement** `ReviewOnce.scala`:

```scala
package orca.review

import orca.{FlowContext, InStage, Par}
import orca.agents.{JsonData, given}
import orca.plan.Title

// Not under capture checking: `derives JsonData` expands tapir's Schema macro,
// which does not type-check there (same split as FixRequest.scala).

/** A change to review once: a one-line `summary`, the repo-relative path of a
  * file holding its unified diff, and the files it changes. The diff stays in a
  * file because read-only reviewers cannot produce it themselves, and pasting a
  * large diff into every reviewer's prompt costs each of them the whole diff.
  */
case class ReviewTarget(
    summary: String,
    diffPath: String,
    changedFiles: List[String]
) derives JsonData

/** One reviewer's findings, named so a report can attribute each one. */
case class ReviewerFindings(reviewer: String, findings: List[ReviewFinding])
    derives JsonData

/** What [[reviewOnce]] found, in reviewer-completion order. */
case class ReviewReport(target: ReviewTarget, byReviewer: List[ReviewerFindings])
    derives JsonData:

  /** The report as markdown, fit to print or post on a PR. */
  def render: String =
    val attributed = byReviewer.flatMap(r => r.findings.map(r.reviewer -> _))
    val header =
      s"## Review: ${target.summary}\n\n" +
        s"${attributed.size} finding(s) from ${byReviewer.size} reviewer(s) " +
        s"across ${target.changedFiles.size} changed file(s)."
    if attributed.isEmpty then s"$header\n\nNo findings reported."
    else s"$header\n\n${attributed.map(renderFinding).mkString("\n")}"

  private def renderFinding(reviewer: String, finding: ReviewFinding): String =
    val where = finding.location.fold("")(l => s" — `${l.text}`")
    val suggestion = finding.suggestion.fold("")(s => s"\n  - suggestion: $s")
    s"- **${finding.title}** ($reviewer)$where\n" +
      s"  - ${finding.description}$suggestion"

/** Review `target` once, without fixing anything: `selection` picks from
  * `reviewers` (by default a cheap picker, after each reviewer's `files:`
  * filter), and the picked reviewers run concurrently.
  */
def reviewOnce(
    reviewers: List[ReviewerAgent[?]],
    target: ReviewTarget,
    selection: ReviewerSelector = ReviewerSelector.agentDriven
)(using FlowContext, InStage): ReviewReport =
  val picked = selection
    .prepare(RosterEntry.roster(reviewers), Title(target.summary), target.changedFiles)
    .apply(Nil)
  val prompt = reviewOncePrompt(target)
  ReviewReport(
    target,
    Par.mapUnordered(MaxConcurrentReviewTasks)(picked): entry =>
      ReviewerFindings(
        entry.name.value,
        entry.agent.resultAs[ReviewResult].autonomous.run(prompt).findings
      )
  )

private def reviewOncePrompt(target: ReviewTarget): String =
  s"Under review: ${target.summary}\n\n" +
    s"The complete diff is in `${target.diffPath}` — read it first.\n\n" +
    ReviewLoopPrompts.ReviewOnce
```

Notes for the implementer:
- `ReviewerSelector.prepare` returns a capture-checked `->` function; calling it from a non-CC file is fine. If the compiler rejects the `.apply(Nil)` call across the CC boundary, assign `val perRound = selection.prepare(…)` and call `perRound(Nil)`.
- `entry.agent` is `private[review]` — available here. Check how `ReviewLoop` runs a reviewer's turn (`reviewWithSession`, `ReviewLoop.scala:550`) and copy its naming/role tags (`withRole(ReviewerPrompts.Role)` or equivalent) so cost attribution matches the loop.
- `ReviewerSlug` → `.value` for the string name.

`ReviewLoopPrompts.scala`:

```scala
  /** What each [[reviewOnce]] reviewer is asked, after the target's summary and
    * diff path.
    */
  val ReviewOnce: String =
    PromptResource.load("/orca/review/prompts/review-once.md")
```

`review-once.md` (moved from `flows/review.sc:817-830`):

```markdown
Review only what the diff changes, plus the code that interacts directly with
it; you may read anything in the repository to check a claim, but do not report
findings in code this change doesn't touch.

Report each finding with: a one-line title, a description with enough context
to act on, the file and line where applicable, and a concrete suggested fix.
Report only what is worth acting on — no nitpicks, no restating what the change
already does well. If nothing in your dimension applies, report no findings.
```

Exports: add `ReviewTarget, ReviewerFindings, ReviewReport, reviewOnce` to the `export orca.review.{…}` block.

- [ ] **Step 4:** Run `sbt --client "flow/testOnly orca.review.*; runner/test"` → PASS.

---

### Task 6: `gh.prHandle`

**Files:**
- Modify: `tools/src/main/scala/orca/tools/GitHubTool.scala` (trait, after `availability`)
- Test: `tools/src/test/scala/orca/tools/PrHandleTest.scala` (or a new `GitHubToolPrHandleTest.scala` beside it)

**Interfaces:**
- Produces: `GitHubTool#prHandle(ref: String): Either[String, PrHandle]` — concrete, so `StubGitHubTool`, `PrFakes` and `FlowLifecycleTest`'s stub need no change.

- [ ] **Step 1: Write the failing tests**:

```scala
package orca.tools

import orca.testkit.StubGitHubTool

class GitHubToolPrHandleTest extends munit.FunSuite:
  private def on(availability: GitHubAvailability): GitHubTool =
    new StubGitHubTool:
      override def availability(): GitHubAvailability = availability

  private val enterprise =
    on(GitHubAvailability.Available("ghe.acme.io", "o", "r"))

  test("a short ref resolves on the checkout's host"):
    assertEquals(
      enterprise.prHandle("acme/widgets#42").map(_.url),
      Right("https://ghe.acme.io/acme/widgets/pull/42")
    )

  test("a PR URL keeps its own host and needs no probe"):
    val unreachable = on(GitHubAvailability.Unavailable(GitHubUnavailable.NoRemote))
    assertEquals(
      unreachable.prHandle("https://github.com/acme/widgets/pull/7").map(_.shortRef),
      Right("acme/widgets#7")
    )

  test("a short ref with GitHub unavailable is a Left naming why"):
    val unreachable = on(GitHubAvailability.Unavailable(GitHubUnavailable.NoRemote))
    assert(unreachable.prHandle("acme/widgets#42").isLeft)

  test("text that is no PR reference is a Left"):
    assert(enterprise.prHandle("the uncommitted changes").isLeft)
```

(Use whatever `GitHubUnavailable` case and `explanation` accessor exist; `NoRemote` is at `GitHubAvailability.scala:20`.)

- [ ] **Step 2:** Run `sbt --client "tools/testOnly orca.tools.GitHubToolPrHandleTest"` → compile failure.

- [ ] **Step 3: Implement** in `trait GitHubTool`:

```scala
  /** A handle for the PR `ref` names: a PR browser URL (its host kept), or
    * `<owner>/<repo>#<number>`, which names no host and so resolves on the host
    * this checkout's GitHub repository is on. `Left` says why there is none.
    */
  def prHandle(ref: String): Either[String, PrHandle] =
    PrHandle.fromUrl(ref.trim).filter(_.url == ref.trim) match
      case Some(handle) => Right(handle)
      case None =>
        for
          issue <- IssueHandle.parse(ref)
          host <- availability() match
            case GitHubAvailability.Available(host, _, _) => Right(host)
            case GitHubAvailability.Unavailable(why) => Left(why.explanation)
          handle <- PrHandle.from(host, issue.owner, issue.repo, issue.number)
        yield handle
```

(`fromUrl` finds the first URL anywhere in `s`; the `.filter` keeps it to a whole-ref match. If `PrHandle.fromExactUrl` is made `private[tools]`, use it instead and drop the filter.)

- [ ] **Step 4:** Run `sbt --client "tools/test"` → PASS.

---

### Task 7: `quick.sc` (rename `simple.sc`)

**Files:**
- Rename: `flows/simple.sc` → `flows/quick.sc` (`git mv`)
- Modify: `shell/src/main/scala/orca/shell/actions/AuthorAction.scala:22,39`, `shell/src/main/scala/orca/shell/create/FlowAuthoring.scala:10,371`, `shell/src/main/scala/orca/shell/menu/AuthoringMenu.scala:156`, `shell/src/main/scala/orca/shell/cli/Cli.scala:254,272`
- Modify: `shell/src/test/scala/orca/shell/actions/AuthorActionTest.scala:71,115`, `shell/src/test/scala/orca/shell/create/AuthoringSandboxTest.scala:30`, `shell/src/test/scala/orca/shell/flows/BuiltInFlowsTest.scala`

- [ ] **Step 1:** `git mv flows/simple.sc flows/quick.sc`. In it: line 1 comment unchanged in meaning; scaladoc title "Quick implement-and-review flow."; usage line `quick.sc`.
- [ ] **Step 2:** Replace `simple.sc` with `quick.sc` at every shell site listed (code and test strings).
- [ ] **Step 3:** `BuiltInFlowsTest`: in the index pin and `bestEffortPrFlows`, `"simple.sc"` → `"quick.sc"` (keep lists sorted).
- [ ] **Step 4:** Run `sbt --client "shell/test"` → PASS.

---

### Task 8: `review.sc` on the library

**Files:**
- Modify: `flows/review.sc` (rewrite below)
- Modify: `runner/src/test/scala/flowtests/FlowCompilesTest.scala:234-258` (`reviewOnlyShape`, `narrowToChangedFiles`)

**Interfaces:**
- Consumes: `ReviewTarget`, `ReviewReport`, `reviewOnce` (Task 5), `gh.prHandle` (Task 6).

- [ ] **Step 1: Rewrite `flows/review.sc`** (header lines 1-4 and the scaladoc kept, with point 1 reworded to "has a cheap-tier agent pick, after each reviewer's `files:` filter"):

```scala
import orca.{*, given}

/** Where the resolver leaves the diff. Fixed rather than per-prompt so a resume
  * finds the same file; removed once the report is out.
  */
val DiffPath: String = ".orca/review.diff"

/** The resolver's answer: the target, plus the `<owner>/<repo>#<number>` ref
  * when it is a GitHub PR, which decides whether the report is also posted.
  */
case class Resolved(target: ReviewTarget, prRef: Option[String])
    derives JsonData

flow(OrcaArgs(args)):
  val resolved = stage("Resolve what to review"):
    resolveTarget()

  if resolved.target.changedFiles.isEmpty then
    fail(s"No changed files found for: ${resolved.target.summary}")

  display(
    s"Reviewing ${resolved.target.summary} — " +
      s"${resolved.target.changedFiles.size} file(s)"
  )

  val report = stage("Review"):
    reviewOnce(allReviewers(reviewAgent), resolved.target)

  display(report.render)

  resolved.prRef.foreach: ref =>
    stage("Post report on the PR"):
      gh.prHandle(ref) match
        case Right(pr) =>
          gh.upsertComment(pr, orcaCommentMarker(userPrompt, "review"), report.render)
        case Left(why) =>
          fail(s"cannot post the report on $ref: $why — post the report " +
            "above on the PR yourself")

  // The diff is scratch, and this flow should leave the tree as it found it. A
  // failed run keeps the file deliberately: the resolve stage is skipped on
  // resume, so the reviewers re-read this same path.
  os.remove.all(os.pwd / os.RelPath(DiffPath))

/** Work out what the prompt refers to and leave its unified diff at
  * [[DiffPath]]. Written to disk rather than returned, so the diff never costs
  * output tokens.
  */
def resolveTarget()(using FlowContext, InStage): Resolved =
  reviewAgent.cheap
    .resultAs[Resolved]
    .autonomous
    .run(
      s"""Work out what change the following request refers to, and write its
         |complete unified diff to `$DiffPath`.
         |
         |Request:
         |$userPrompt
         |
         |The request may name a GitHub PR (a `<owner>/<repo>#<number>` ref or
         |a URL), a branch, a commit or commit range, the uncommitted local
         |changes — or it may BE the diff itself, pasted or piped in. Pick
         |whichever reading fits; when in doubt prefer the local working tree.
         |
         |Write the diff with a shell redirect (`git diff … > $DiffPath`, `gh
         |pr diff … > $DiffPath`, or a heredoc when the request already carries
         |the diff). Do NOT reproduce the diff in your answer.
         |
         |Then report: `target.summary`, a one-line summary of what is under
         |review (e.g. "PR acme/widgets#42: add pagination"); `target.diffPath`,
         |exactly `$DiffPath`; `target.changedFiles`, the repo-relative paths of
         |the changed files; and `prRef`, only when the target is a GitHub PR,
         |its `<owner>/<repo>#<number>` ref.""".stripMargin
    )
```

The old flow wrapped its target in a single-property envelope because a cheap model stuffed a multi-property schema under its first property. `Resolved` has two properties; if live runs show the same failure, wrap it again (`case class ResolvedEnvelope(resolved: Resolved)`) — don't pre-emptively.

- [ ] **Step 2:** `FlowCompilesTest`: replace `reviewOnlyShape` + `narrowToChangedFiles` with

```scala
  /** `flows/review.sc`: one review pass over a resolved target, no coder
    * session, report rendered and posted on a PR resolved from its ref.
    */
  def reviewOnlyShape(): Unit =
    flow(OrcaArgs()):
      val report: ReviewReport = stage("review"):
        reviewOnce(
          allReviewers(reviewAgent),
          ReviewTarget("summary", ".orca/review.diff", List("a.scala"))
        )
      stage("post"):
        gh.prHandle("acme/widgets#1") match
          case Right(pr) =>
            gh.upsertComment(pr, orcaCommentMarker(userPrompt, "review"), report.render)
          case Left(_) => ()
```

Keep the `Location` / `filePattern` pins if no other canary covers them (grep `filePattern` in the file; move the two `val _ : …` lines into `reviewerCustomisationSurface` if needed).

- [ ] **Step 3:** Run `sbt --client "runner/test; shell/test"` → PASS. Then the integration compile: `ORCA_INTEGRATION=1 sbt publishLocal "shell/testOnly *BuiltInFlowsCompileTest"` → PASS for `review.sc`.

---

### Task 9: `epic.sc`; drop `implement-enhanced.sc`, `implement-interactive.sc`, `examples/runnable/02-interactive`

**Files:**
- Create: `flows/epic.sc`
- Delete: `flows/implement-enhanced.sc`, `flows/implement-interactive.sc`, `examples/runnable/02-interactive/`
- Modify: `flows/implement.sc:168-169` (drop the `implement-interactive.sc` pointer)
- Modify: `build.sbt:260-273` (authoring examples: `implement.sc`, `epic.sc`)
- Modify: `shell/src/main/scala/orca/shell/create/FlowAuthoring.scala:25,387,460`; `shell/src/test/scala/orca/shell/create/FlowAuthoringTest.scala:29,77,490,547` (API-file references only — the `forkFilenameDefault` tests use `"implement-interactive.sc"` as an arbitrary input name and stay)
- Modify: `shell/src/test/scala/orca/shell/flows/BuiltInFlowsTest.scala`
- Modify: `runner/src/test/scala/flowtests/FlowCompilesTest.scala:474-519`

**Interfaces:**
- Consumes: `Plan.autonomous.roadmap`, `Roadmap.reviewed`, `Roadmap#epicPrompt`, `Epic` (Task 4); runtime base fix (Task 1).

- [ ] **Step 1: Create `flows/epic.sc`**:

```scala
// Plan a large change as epics, then plan, build and review each epic in turn.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21

/** Epic-sized planning + coding flow, for a change too large for one plan.
  *
  * The planner splits the prompt into epics and critiques that outline. Each
  * epic is planned into tasks just before it runs, so its planner reads the
  * code earlier epics produced. Every task gets one review pass, every epic a
  * review loop over everything it changed, and the whole run a final review
  * loop. A documentation stage updates the project's docs before the final
  * review.
  *
  * A PR follows when the repository is on GitHub; otherwise the run says so and
  * ends on the feature branch, work committed either way.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" epic.sc -- "Add user accounts: storage, sign-up, login and password reset"
  * ```
  *
  * Requires the configured role agents logged in (`claude` by default); `gh` is
  * optional.
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val roadmap = stage("Plan epics"):
    Plan.autonomous.roadmap(userPrompt, planningAgent).reviewed().value

  val epicOpenFindings =
    for epic <- roadmap.epics yield
      stage(s"Epic: ${epic.title}"):
        val plan = stage("Plan"):
          Plan.autonomous
            .from(roadmap.epicPrompt(epic), planningAgent)
            .reviewed()
            .value
        val seed = s"${roadmap.brief}\n\n---\n\n${plan.brief}"

        val taskOpenFindings =
          for task <- plan.tasks yield
            stage(s"Task: ${task.title}"):
              val session = codingAgent.session("implementer", seed = seed)
              session.run(task.description)
              reviewThenFix(
                coderSession = session,
                reviewers = allReviewers(reviewAgent),
                task = task
              )

        // Before the next epic builds on this one: everything it changed.
        val epicFixer = codingAgent.session("epic-fixer", seed = seed)
        reviewAndFixLoop(
          coderSession = epicFixer,
          reviewers = allReviewers(reviewAgent),
          task = Task(epic.title, epic.goal),
          maxFixTurns = 3,
          priorOpenFindings = taskOpenFindings.flatMap(_.findings)
        )

  // Its own stage, so the docs commit exists before the push below. The
  // documenter implemented none of the epics, so the prompt points it at the
  // branch diff for what actually changed.
  stage("Update documentation"):
    val documenter = codingAgent.session("documenter", seed = roadmap.brief)
    documenter.run(
      "All epics are done and committed. Read what this branch changed " +
        "(`git diff` against its base), then update project docs (README, " +
        "doc-comments) to match. Only update what's affected — no new sections."
    )

  // Nothing reviews again after this loop, hence the raised fix-turn cap.
  val openFindings = stage("Final review"):
    val finalFixer = codingAgent.session("final-fixer", seed = roadmap.brief)
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), roadmap.description),
      diff = ReviewDiff.WholeRun,
      maxFixTurns = 5,
      priorOpenFindings = epicOpenFindings.flatMap(_.findings)
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent,
    openFindings = openFindings
  )
```

Check: an empty `roadmap.epics` should `fail("The planner produced no epics")` right after the "Plan epics" stage — add it.

- [ ] **Step 2:** Delete the two flows and `examples/runnable/02-interactive/`. In `flows/implement.sc` delete the closing "For the variant where the planner can ask clarifying questions…" paragraph.
- [ ] **Step 3:** `build.sbt`: examples list → `base / "flows" / "implement.sc", base / "flows" / "epic.sc"`. `FlowAuthoring.scala`: `bundledNames` and both `example2` → `"epic.sc"`. `FlowAuthoringTest.scala`: lines 29, 77, 490, 547 → `epic.sc`.
- [ ] **Step 4: `BuiltInFlowsTest`** — index pin becomes (sorted) `epic.sc, implement.sc, issue-pr-bugfix.sc, issue-pr.sc, quick.sc, review.sc` (Task 10 finishes it); `taskBasedFlows` drops the two removed flows and adds `epic.sc`; `bestEffortPrFlows` likewise.
- [ ] **Step 5: `FlowCompilesTest`**: delete `interactivePlanFlowShape` and `enhancedImplementFlowShape`; add

```scala
  /** `epic.sc`: roadmap → per epic a nested plan stage, task stages and an epic
    * review loop → final review → PR.
    */
  def epicFlowShape(): Unit =
    flow(OrcaArgs()):
      val roadmap: Roadmap = stage("Plan epics"):
        Plan.autonomous.roadmap(userPrompt, claude).reviewed().value
      val perEpic: List[OpenFindings] =
        for epic <- roadmap.epics yield stage(s"Epic: ${epic.title}"):
          val plan: Plan = stage("Plan"):
            Plan.autonomous.from(roadmap.epicPrompt(epic), claude).value
          val tasks =
            for task <- plan.tasks yield stage(s"Task: ${task.title}"):
              val session = claude.session("implementer", seed = plan.brief)
              session.run(task.description)
              reviewThenFix(session, allReviewers(claude), task)
          reviewAndFixLoop(
            coderSession = claude.session("epic-fixer", seed = plan.brief),
            reviewers = allReviewers(claude),
            task = Task(epic.title, epic.goal),
            maxFixTurns = 3,
            priorOpenFindings = tasks.flatMap(_.findings)
          )
      val _ = perEpic
```

- [ ] **Step 6:** Run `sbt --client "runner/test; shell/test"` → PASS (index pin still lists `issue-pr*` until Task 10). Then `ORCA_INTEGRATION=1 sbt publishLocal "shell/testOnly *BuiltInFlowsCompileTest"` → `epic.sc` compiles.

---

### Task 10: `resolve.sc`; drop `issue-pr.sc`, `issue-pr-bugfix.sc`

**Files:**
- Create: `flows/resolve.sc`
- Delete: `flows/issue-pr.sc`, `flows/issue-pr-bugfix.sc`
- Modify: `shell/src/test/scala/orca/shell/flows/BuiltInFlowsTest.scala`
- Modify: `shell/src/test/scala/orca/shell/flows/FlowCatalogTest.scala:46` (`"issue-pr.sc"` → `"resolve.sc"`, description string adjusted)
- Modify: `runner/src/test/scala/flowtests/FlowCompilesTest.scala` (add `resolveFlowShape`)

**Interfaces:**
- Consumes: `Triage` (Task 3), `BugReportMatch`, `IssueHandle`, `BranchNamingStrategy.issue`, `orcaCommentMarker`, `openPrIfGitHub(summarisingAgent, openFindings, title, body, context, instructions)`.

- [ ] **Step 1: Create `flows/resolve.sc`**:

```scala
// Resolve a request or GitHub issue: triage, reproduce a bug, fix or build it, PR.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21

/** Request → triage → fix or change → PR, fully autonomous.
  *
  * The prompt is any request: a bug report, a feature request, a change — or a
  * GitHub issue reference (`<owner>/<repo>#<number>` or the issue's URL), in
  * which case the issue is read and the branch is named after it
  * (`fix/issue-<n>`), so a re-run after a crash lands on the same branch.
  *
  * Triage checks the request against the repository and either rejects it — the
  * reply is posted on the issue, or printed — or accepts it as one of:
  *
  *   - a bug a test can show: a failing test is written and checked to fail
  *     the way the request describes, before anything is fixed;
  *   - a bug no test can show: fixed anyway; the PR (and the issue) say there
  *     is no automated reproduction and list the steps;
  *   - a change: planned and implemented directly.
  *
  * Accepted work is planned, implemented task by task with a single review pass
  * each, reviewed as a whole in a loop, and opened as a PR when the repository
  * is on GitHub.
  *
  * ```bash
  * scala-cli run --workspace "$(mktemp -d)" resolve.sc -- "acme/widgets#42"
  * scala-cli run --workspace "$(mktemp -d)" resolve.sc -- "Dividing by zero crashes the calculator"
  * ```
  *
  * Use the same prompt on re-runs: the progress log and the issue-comment
  * marker are keyed on it.
  *
  * Requires the configured role agents logged in (`claude` by default); `gh`
  * when the prompt is an issue reference.
  */

import orca.{*, given}

val orcaArgs = OrcaArgs(args)

/** The issue the prompt names, when the whole prompt is an issue reference. A
  * PR URL also parses as one, but a PR is not a request to resolve.
  */
val issueHandle: Option[IssueHandle] =
  if orcaArgs.userPrompt.contains("/pull/") then None
  else IssueHandle.parse(orcaArgs.userPrompt).toOption

/** Write a test at `testPath` that fails the way `request` describes, and have
  * a second agent confirm it. The reproducer retries once when the check
  * disagrees; a second disagreement fails the stage, so a re-run starts over.
  */
def reproduce(request: String, testPath: String)(using
    FlowContext,
    FlowControl,
    InStage,
    WorkspaceWrite
): Unit =
  val reproducer = codingAgent.session("reproducer", seed = request)
  reproducer.run(
    s"""Write a focused test at `$testPath` that fails on the current code in
       |the way the request describes. Run only that test and confirm it
       |fails.""".stripMargin
  )
  val first = checkReproduction(request, testPath)
  if !first.matches then
    reproducer.run(
      s"""A separate check says the test does not reproduce the request:
         |${first.explanation}
         |
         |Fix the test at `$testPath`, then run it again.""".stripMargin
    )
    val second = checkReproduction(request, testPath)
    if !second.matches then
      fail(s"Could not reproduce the request: ${second.explanation}")

/** Run the test at `testPath` and judge whether its failure is the one
  * `request` describes. Full tier: a wrong "matches" lets a bogus reproduction
  * through, a wrong "doesn't" aborts a sound one.
  */
def checkReproduction(request: String, testPath: String)(using
    FlowContext,
    InStage
): BugReportMatch =
  val testHint = stackSettings.test match
    case Nil      => ""
    case commands => s"\nThe project's test command: ${commands.mkString(" && ")}"
  codingAgent
    .resultAs[BugReportMatch]
    .autonomous
    .run(
      s"""Run only the test at `$testPath`. It must fail, and the failure must
         |be the defect the request below describes — a passing test, or a
         |failure for another reason (compile error, wrong assertion), does not
         |match.$testHint
         |
         |Request:
         |$request""".stripMargin
    )

/** What the planner is asked: the request, triage's findings, and for a
  * testable bug the committed test the fix must turn green.
  */
def planningInput(request: String, brief: String, kind: Triage.Kind): String =
  val testNote = kind match
    case Triage.Kind.TestableBug(path) =>
      s"\n\nA failing test at `$path` reproduces the bug and is committed on " +
        "this branch. The fix must make it pass without breaking other tests."
    case _ => ""
  s"$request\n\nTriage findings:\n$brief$testNote"

/** The PR body: the generated summary, the issue it closes, and for an
  * untestable bug the steps reviewers can reproduce it with.
  */
def prBody(summary: String, kind: Triage.Kind): String =
  val repro = kind match
    case Triage.Kind.UntestableBug(steps) =>
      s"\n\n## No automated reproduction\n\nNo focused test can show this " +
        s"bug, so none was added. To reproduce it by hand:\n\n$steps"
    case _ => ""
  val closes = issueHandle.fold("")(i => s"\n\nCloses ${i.shortRef}.")
  s"$summary$repro$closes"

flow(orcaArgs, branchNaming = issueHandle.map(BranchNamingStrategy.issue(_))):
  val request = issueHandle.fold(userPrompt): handle =>
    val issue = gh.readIssue(handle)
    s"""Issue ${handle.shortRef}: ${issue.title}
       |Reporter: ${issue.author}
       |
       |${issue.body}""".stripMargin

  val triage = stage("Triage"):
    Plan.autonomous.triage(request, planningAgent).value

  triage match
    case Triage.Reject(reply) =>
      issueHandle match
        case Some(issue) =>
          stage("Comment: rejection"):
            gh.upsertComment(issue, orcaCommentMarker(userPrompt, "reject"), reply)
        case None => display(reply)

    case Triage.Accept(_, brief, kind) =>
      kind match
        case Triage.Kind.TestableBug(testPath) =>
          stage("Reproduce"):
            reproduce(request, testPath)
        case Triage.Kind.UntestableBug(steps) =>
          issueHandle.foreach: issue =>
            stage("Comment: reproduction steps"):
              gh.upsertComment(
                issue,
                orcaCommentMarker(userPrompt, "repro-steps"),
                s"## Reproduction\n\n$steps"
              )
        case Triage.Kind.Change => ()

      val plan = stage("Plan"):
        Plan.autonomous
          .from(planningInput(request, brief, kind), planningAgent)
          .reviewed()
          .value

      val taskOpenFindings =
        for task <- plan.tasks yield
          stage(s"Task: ${task.title}"):
            val session = codingAgent.session("implementer", seed = plan.brief)
            session.run(task.description)
            reviewThenFix(
              coderSession = session,
              reviewers = allReviewers(reviewAgent),
              task = task,
              userRequest = Some(request)
            )

      // Nothing reviews again after this loop, hence the raised fix-turn cap.
      val openFindings = stage("Final review"):
        val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)
        reviewAndFixLoop(
          coderSession = finalFixer,
          reviewers = allReviewers(reviewAgent),
          task = Task(Title("The whole planned change"), plan.brief),
          userRequest = Some(request),
          diff = ReviewDiff.WholeRun,
          maxFixTurns = 5,
          priorOpenFindings = taskOpenFindings.flatMap(_.findings)
        )

      openPrIfGitHub(
        summarisingAgent = codingAgent,
        openFindings = openFindings,
        body = summary => prBody(summary.body, kind),
        context = issueHandle.map(i => s"Originating issue: ${i.shortRef}")
      )
```

Implementer notes: adjust each helper's `using` clause to exactly what compiles (`session` needs `FlowControl`; `run` needs the stage tokens). `stackSettings` is the accessor name if it exists — check `accessors.scala`; otherwise read `summon[FlowContext].stackSettings`. A match whose arms return different types as the flow body's last expression may warn under `-Wvalue-discard` in scala-cli; if so, `val _ = openPrIfGitHub(…)` (the test accepts it).

- [ ] **Step 2:** Delete `flows/issue-pr.sc`, `flows/issue-pr-bugfix.sc`.

- [ ] **Step 3: `BuiltInFlowsTest`**:
  - index pin: `List("epic.sc", "implement.sc", "quick.sc", "resolve.sc", "review.sc")`;
  - `taskBasedFlows = List("epic.sc", "implement.sc", "resolve.sc")`;
  - `bestEffortPrFlows = List("epic.sc", "implement.sc", "quick.sc", "resolve.sc")`;
  - `requiredPrFlows = Nil`; `assertEquals(ownBodyPrFlows, Nil)`;
  - snapshot test reads `implement.sc` instead of `issue-pr.sc`;
  - replace `lastStatement` + "the best-effort PR step is each flow's last statement" with a block-end check, and extract the paren scan from `finalReviewCall` into `callAt(text: String, open: Int): String` shared by both:

```scala
  /** Whether `name`'s `openPrIfGitHub(...)` call ends the block it sits in:
    * every non-blank line after its closing paren is indented less than the
    * line the call starts on. Covers a flow that calls it last in a match arm.
    */
  private def prStepEndsItsBlock(name: String): Boolean =
    val text = resourceText(name)
    val open = text.lastIndexOf("openPrIfGitHub(")
    val lineStart = text.lastIndexOf('\n', open) + 1
    val indent = text.substring(lineStart).takeWhile(_ == ' ').length
    val after = text.substring(open + callAt(text, open).length)
    after.linesIterator
      .drop(1) // the rest of the call's closing line
      .filter(_.trim.nonEmpty)
      .forall(_.takeWhile(_ == ' ').length < indent)

  test("the best-effort PR step ends its flow's block"):
    bestEffortPrFlows.foreach: name =>
      assert(prStepEndsItsBlock(name), name)
```

- [ ] **Step 4: `FlowCompilesTest`** — add:

```scala
  /** `resolve.sc`: optional issue → triage → reject comment, or reproduce /
    * comment by kind → plan → final review → PR with a custom body.
    */
  def resolveFlowShape(): Unit =
    val handle: Option[IssueHandle] = IssueHandle.parse("acme/w#1").toOption
    flow(OrcaArgs(), branchNaming = handle.map(BranchNamingStrategy.issue(_))):
      val triage: Triage = stage("Triage"):
        Plan.autonomous.triage(userPrompt, claude).value
      triage match
        case Triage.Reject(reply) =>
          handle.foreach: issue =>
            stage("Comment"):
              gh.upsertComment(issue, orcaCommentMarker(userPrompt, "reject"), reply)
        case Triage.Accept(_, brief, kind) =>
          val verdict: BugReportMatch = stage("Reproduce"):
            claude.resultAs[BugReportMatch].autonomous.run(brief)
          val _ = (verdict.matches, kind)
          val openFindings = stage("Final review"):
            reviewAndFixLoop(
              coderSession = claude.session("final-fixer", seed = brief),
              reviewers = allReviewers(claude),
              task = Task(Title("t"), brief),
              diff = ReviewDiff.WholeRun,
              maxFixTurns = 5
            )
          val _ = openPrIfGitHub(
            summarisingAgent = claude,
            openFindings = openFindings,
            body = s => s.body,
            context = handle.map(_.shortRef)
          )
```

- [ ] **Step 5:** In `FlowCompilesTest`, update the scaladoc of the remaining canaries that cite removed flows (`summarisePr` at ~`:277` "exercised by `flows/issue-pr.sc`", the gh comment surface at ~`:312`, the branch + PR surface at ~`:342` "`flows/implement-enhanced.sc`") to cite `resolve.sc` / `epic.sc`.

- [ ] **Step 6:** Run `sbt --client "runner/test; shell/test"` → PASS. Then `ORCA_INTEGRATION=1 sbt publishLocal "shell/testOnly *BuiltInFlowsCompileTest"` → all five flows compile.

---

### Task 11: Docs and the flow guideline

**Files:**
- Modify: `AGENTS.md` (Conventions → new "### Built-in flows" after "### Code style")
- Rewrite: `docs/using/built-in-flows.md`
- Modify: `docs/authoring/planning.md`, `docs/api/data-structures.md`, `docs/authoring/extending.md:94`, `docs/using/shell.md:60`
- Modify: `examples/runnable/README.md`, `examples/runnable/01-simple/README.md:11`, `skills/orca/SKILL.md` (flow names in its options list), `README.md` (only if it names a removed flow — it currently names `implement.sc` only)
- Modify: the docs page listing review / gh operations (find with `grep -rln "upsertComment\|reviewAndFixLoop" docs/api docs/authoring`) — add `reviewOnce`, `ReviewReport.render`, `gh.prHandle`

- [ ] **Step 1: `AGENTS.md`** — add:

```markdown
### Built-in flows

- `flows/*.sc` are examples users read and fork, so keep each one short: its
  stages, their order and its decisions visible at a glance.
- Move logic that is generic, or that isn't the flow's own shape, into the
  library: parsing, rendering, reviewer selection, host resolution, prompt
  assembly, retry policies. A flow keeps only what makes it that flow.
- Prefer a library helper used by one flow over 30 lines in a script; prefer
  the script when the code *is* the flow's shape (the stage sequence).
```

- [ ] **Step 2: `docs/using/built-in-flows.md`** — rewrite the flow sections (keep the intro paragraphs; keep "To resume a run…"):

```markdown
## `quick.sc`

No planning: the prompt is the one task, handed straight to the coder and then
reviewed in a loop. For small, well-scoped changes, where a plan would be
overhead. It is also the flow that `orca create` and `orca fork` run.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/quick.sc).

## `implement.sc`

The default choice. It plans the prompt into tasks, implements each task on the
run's branch and reviews it once, then runs a review-and-fix loop over the
whole change.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/implement.sc).

## `epic.sc`

For a change too large for one plan. The planner splits the prompt into epics
and critiques that outline. Each epic is planned into tasks just before it
runs, so it builds on the code earlier epics produced; its tasks are reviewed
once each, and the epic as a whole in a loop. A documentation stage and a final
review over the whole change follow.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/epic.sc).

## `resolve.sc`

You give it a request — a bug report, a feature request, any change — or a
GitHub issue as `owner/repo#N` or a URL. Triage checks the request against the
repository and either rejects it (the reply goes on the issue, or is printed)
or accepts it. For a bug a test can show, the flow first writes a failing test
and checks it fails the way the request says. A bug no test can show is fixed
anyway, and the PR says so. Then it plans, implements and reviews like
`implement.sc`. For an issue, the branch is `fix/issue-<n>` and the PR closes
the issue; `gh` is needed then.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/resolve.sc).

## `review.sc`

(unchanged text)
```

Examples block: `implement.sc`, `epic.sc "Add user accounts: storage, sign-up, login"`, `resolve.sc "acme/widgets#42"`, `resolve.sc "Dividing by zero crashes the calculator"`, the two `review.sc` lines. Runnable examples section: only `01-simple`.

- [ ] **Step 3: `docs/authoring/planning.md`** — operations list: `from`, `roadmap` (returns a `Roadmap(description, epics, brief)`; `roadmap.epicPrompt(epic)` is the input for planning one epic with `from`; `.reviewed()` works on it), `triage` (returns `Triage.Reject(reply)` or `Triage.Accept(summary, brief, kind)` with `kind` `TestableBug(failingTestPath)` / `UntestableBug(reproductionSteps)` / `Change`). Delete the `assessThenPlan` / `Verdict` text; "Verdicts and triage" section becomes "Triage", pointing at `flows/resolve.sc`; `BugReportMatch` is described as checking a failing test's output. `Plan(id, …)`.
- [ ] **Step 4: `docs/api/data-structures.md`** — remove `Verdict` (also from the non-`JsonData` sentence at line 7, leaving `WithChat`); add `Roadmap`, `Epic`, new `Triage`, `ReviewTarget` / `ReviewReport`. `docs/authoring/extending.md:94`: `PlanPrompts`: `Planning`, `Roadmap`, `RoadmapReview`, `Triage`, `Review`. `docs/using/shell.md:60`: `quick.sc`.
- [ ] **Step 5:** `examples/runnable/README.md`: drop the 02 row; the "other flow scripts" sentence lists `epic.sc`, `resolve.sc`, `review.sc`. `01-simple/README.md:11`: drop the 02 link sentence. `skills/orca/SKILL.md`: update any flow list to the five names.
- [ ] **Step 6:** Verify nothing outside history still names a removed flow or type:

Run: `grep -rn "simple\.sc\|implement-enhanced\|implement-interactive\|issue-pr\|assessThenPlan\|Verdict\|BugTriage\|02-interactive\|epicId" --include=*.scala --include=*.md --include=*.sc --include=*.sh --include=*.sbt . | grep -v "^./adr/\|^./docs/research/\|^./docs/plans/\|^./docs/superpowers/"`
Expected: only the `forkFilenameDefault` test inputs in `FlowAuthoringTest.scala`.

- [ ] **Step 7:** `sbt --client scalafmtAll`, then `sbt --client test` → PASS. Build the docs concat (`sbt --client shell/compile`) so `orca-docs.md` regenerates.

---

## Self-Review Notes

- Spec coverage: flow set (Tasks 7–10), runtime base (1), epics (4), triage (3), reviewOnce (5), prHandle (6), `epicId` rename (2), docs + guideline (11), test pins (7, 9, 10).
- `Plan.interactive.roadmap` kept for the documented mode × operation grid.
- Spec deviation, recorded in the spec: `Triage` is `Reject` / `Accept(summary, brief, kind)` rather than four flat cases.
