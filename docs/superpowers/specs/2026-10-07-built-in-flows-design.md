# Built-in flows: reshape the shipped set

## Goal

The shipped flows cover a wider range of work, in three implementation flows of
increasing scope plus two task-specific ones. Flows that duplicate others are
removed. Generic logic moves out of the scripts into the library, so each flow
reads as a short, forkable example.

## Flow set

| Flow           | Replaces                              | Shape                                                                                         |
| -------------- | ------------------------------------- | --------------------------------------------------------------------------------------------- |
| `quick.sc`     | `simple.sc`                           | Prompt is the one task: implement, review-and-fix loop (cap 3), PR.                           |
| `implement.sc` | `implement.sc`                        | Unchanged shape: plan → per task implement + single review pass → whole-run loop (cap 5) → PR. Header drops the `implement-interactive.sc` pointer. |
| `epic.sc`      | `implement-enhanced.sc`               | Roadmap of epics → per epic: plan tasks, implement, review → docs → whole-run loop → PR.      |
| `resolve.sc`   | `issue-pr.sc`, `issue-pr-bugfix.sc`   | Freeform request or GH issue → triage → reject, or (repro test if bug) → plan → implement → PR. |
| `review.sc`    | `review.sc`                           | Same behaviour; generic parts moved to the library.                                           |

Removed: `simple.sc`, `implement-enhanced.sc`, `implement-interactive.sc`,
`issue-pr.sc`, `issue-pr-bugfix.sc`, `examples/runnable/02-interactive/`.
`Plan.interactive` stays in the library.

Names were checked by a critique agent: `quick`/`implement`/`epic` signal scale
without reusing "task" (the library's plan unit), and keep `implement.sc` as the
guessable default. `resolve.sc` covers all outcomes; `triage.sc` would suggest
it stops after triage.

### `quick.sc`

`simple.sc` renamed, behaviour unchanged. Still the flow `orca create` /
`orca fork` run.

### `epic.sc`

1. Stage "Plan epics": `Plan.autonomous.roadmap(userPrompt, planningAgent)
   .reviewed().value`.
2. Per epic, stage `Epic: <title>`:
   1. Nested stage "Plan": `Plan.autonomous.from(roadmap.epicPrompt(epic),
      planningAgent).reviewed().value`. Planned just before the epic runs, so
      the planner reads the code earlier epics produced.
   2. Per task, nested stage `Task: <title>`: implementer session seeded with
      the roadmap brief and the epic plan's brief, then `reviewThenFix`.
   3. `reviewAndFixLoop` over the epic stage's diff (`SampleFromStage`,
      `maxFixTurns = 3` spelled out), coder session `epic-fixer` seeded like the
      implementers, with the tasks' open findings as `priorOpenFindings`.
      Correct across a mid-epic resume thanks to the stage-base change below.
3. Stage "Update documentation" (as in `implement-enhanced.sc`).
4. Stage "Final review": `reviewAndFixLoop` with `ReviewDiff.WholeRun`, cap 5,
   seeded with the epic loops' open findings.
5. `openPrIfGitHub`.

### `resolve.sc`

**Input.** If the whole prompt parses via `IssueHandle.parse` and is not a
`/pull/` URL, read the issue (outside a stage, as today: an issue edited
between attempts changes the request on resume);
the request is its title, reporter and body, and the branch is named with
`BranchNamingStrategy.issue`. Otherwise the prompt is the request, with default
branch naming. An issue ref embedded in longer text is freeform input (the
triage agent can still read it over the network; no comments, no `Closes`).

**Stages.**

1. "Triage": `Plan.autonomous.triage(request, planningAgent).value`.
2. By outcome:
   - `Reject(reply)`: issue → stage "Comment: rejection" running
     `gh.upsertComment(issue, orcaCommentMarker(userPrompt, "reject"), reply)`
     (needs `WorkspaceWrite`, hence the stage); otherwise `display`. The run
     ends successfully.
   - `Accept` with `Kind.TestableBug(failingTestPath)`: one stage "Reproduce":
     a `reproducer` session writes the test at `failingTestPath` and runs only
     that test; then a separate full-tier `codingAgent` turn runs only that
     test (the `test` stack setting is given as a hint for the command) and
     returns a `BugReportMatch`. On no match (the test passes, or fails for a
     different reason) the reproducer is told why and retries once; a second
     no-match fails the stage. One stage, so a failed reproduction is not
     recorded and a re-run starts it over.
   - `Accept` with `Kind.UntestableBug(reproductionSteps)`: issue → stage
     "Comment: reproduction steps" upserting the steps (marker `repro-steps`).
   - `Accept` with `Kind.Change`: nothing extra.
3. `Accept`: stage "Plan" — `Plan.autonomous.from(...)
   .reviewed()`, input built from the request and the triage brief (plus the
   committed failing test's path for a testable bug). Then per-task implement +
   `reviewThenFix`, then "Final review" (`WholeRun`, cap 5), passing the request
   as `userRequest`. Written inline, not as a library call: it is the flow's
   main shape.
4. `openPrIfGitHub` (the last statement of the non-reject branch) with `body` adding `Closes owner/repo#N` (issue input) and,
   for an untestable bug, a "No automated reproduction" section with the steps.

No tentative PR, no CI wait, no CI-failure comment.

### `review.sc`

Keeps: the resolver stage (writes the diff to `.orca/review.diff`, returns
summary, changed files, optional PR ref), display, posting on the PR, removing
the diff file. Reviewer picking, the review prompt, the fan-out and report
rendering move to `reviewOnce` / `ReviewReport`; PR-handle resolution moves to
`gh.prHandle`. Reviewers keep reading the diff from the file, so large PRs are
not pasted into every prompt.

## Library changes

### `orca.plan`: epics

- `case class Epic(title: Title, goal: String) derives JsonData`.
- `case class Roadmap(description: String, epics: List[Epic], brief: String)
  derives JsonData`, with `Announce` listing the epics.
- `Roadmap#epicPrompt(epic: Epic): String`: the roadmap description, the epics
  already done, this epic's goal, and an instruction to plan only this epic
  against the current code.
- `Plan.{autonomous,interactive}.roadmap(userPrompt, agent, instructions =
  PlanPrompts.Roadmap): WithChat[Roadmap]`.
- `.reviewed(instructions = PlanPrompts.RoadmapReview, variant)` on
  `WithChat[Roadmap]`, defined in `object Roadmap` (two defaulted `reviewed`
  overloads cannot share `object Plan`).
- New prompt resources `roadmap.md`, `roadmap-review.md`.
- An epic's task plan is a plain `Plan`. `Plan.epicId` is renamed to `Plan.id`
  so it does not read as a reference to `Epic`.

### `orca.plan`: one triage

Removed: `assessThenPlan` (both modes), `AssessedPlan`, `Verdict` (with its
`RejectionKind`: the flow only posts the reply; the prompt tells the agent how
to word it), `PlanPrompts.AssessThenPlan`, `assess-then-plan.md`.

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

Two top-level cases so a flow handles "reject" and "do the work" once each,
with `kind` deciding only the extra steps.

- `brief`: what triage verified (files, root cause when known), seeding the
  planner. Replaces continuing the triage chat, which does not survive a resume.
- Wire record `BugTriage` → `TriageReply`: flat, `kind` plus per-case fields,
  checked by `toTriage` post-decode. `branchName` dropped.
- `triage.md` rewritten: the skeptical-assessment checklist from
  `assess-then-plan.md` plus the four-way classification.
- `Plan.{autonomous,interactive}.triage` keep their signatures.
- `BugReportMatch` stays; its doc refers to test output, not CI.
- The `PlanPrompts.Triage` default stays, pointing at the rewritten `triage.md`.

### `orca.review`: review without fixing

- `reviewOnce(reviewers: List[ReviewerAgent[?]], target: ReviewTarget)(using
  FlowContext, InStage): ReviewReport`, in `orca.review` (it builds
  `RosterEntry`s, whose constructor is `private[review]`). `ReviewTarget`
  (`summary`, `diffPath`, `changedFiles`) is what `review.sc`'s resolver
  returns today, moved to the library. Picks reviewers with
  `ReviewerSelector.agentDriven` (the `files:` pre-filter applies), runs them
  concurrently with `review.sc`'s current prompt (moved to a prompt resource:
  read the diff file, report only on what it changes), no fixer, no lint. It
  does not share the loop's diff delivery.
- `ReviewReport`: findings attributed to reviewers, `derives JsonData` (a stage
  result; in a file without capture checking), with `render: String` — the
  markdown renderer moved from `review.sc` (`ReviewFormatting` is plain-text
  event-log output and does not fit).

### `orca.tools`

- `GitHubTool#prHandle(ref: String): Either[String, PrHandle]`: a concrete
  trait method (stubs need no change) that parses `owner/repo#N` or a PR URL
  and resolves the host via `availability()`, using `PrHandle.from` /
  `PrHandle.fromUrl`.

### Runtime: resume-safe stage base commit

A stage's base commit (what `SampleFromStage` diffs against) is read from HEAD
when the stage is entered and held only in memory. A stage that crashed after
commits were made inside it (by nested stages, or an agent committing) gets a
later base on resume, and its review misses those commits.

Fix: when a stage is entered fresh, append a progress-log entry recording its
id and base commit. When a stage with such an entry but no result is entered
again, it uses the recorded base if that commit is still an ancestor of HEAD,
and HEAD otherwise (with a `Step` saying so). Amend ADR 0018 §2.1. The on-disk
format changes without compatibility handling.

## Documentation

- `AGENTS.md` → Conventions, new "Built-in flows" subsection: flows stay short
  and readable, as examples users fork; logic that is generic or not about the
  flow's shape (rendering, parsing, selection, host resolution, prompt
  assembly) goes into the library; a flow keeps its stages and their order
  visible.
- `docs/using/built-in-flows.md`: rewritten for the new set; the runnable
  examples section drops `02-interactive`.
- `docs/authoring/planning.md`, `docs/api/data-structures.md`,
  `docs/authoring/extending.md`: roadmap, new `Triage`, no `Verdict` /
  `assessThenPlan`; `reviewOnce` and `gh.prHandle` in the API pages that list
  review and gh operations.
- `docs/using/shell.md`, `examples/runnable/README.md`,
  `examples/runnable/01-simple/README.md`, `skills/orca/SKILL.md`, `README.md`:
  flow names.
- `build.sbt`: the shell's authoring API examples become `implement.sc` and
  `epic.sc` (was `implement-interactive.sc`).
- ADRs, `docs/research/`, `docs/plans/`, `docs/superpowers/plans/` are history
  and are not edited.

## Code and tests

- `BuiltInFlowsTest`: update the flow-list pins (`taskBasedFlows`,
  `requiredPrFlows`, `ownBodyPrFlows` → `Nil`, the direct read of
  `issue-pr.sc`), let the "PR step is last" check accept `resolve.sc`'s PR
  call as the last statement of its non-reject branch, and make
  `finalReviewCall` pick the stage named exactly "Final review" so it does not
  land on `epic.sc`'s per-epic loop.
- `flow/src/test/scala/orca/plan/CannedResult.scala`: doc names the new wire
  types.
- Progress log: tests for the stage-start entry and for the recorded base being
  used on re-entry (and dropped when no longer an ancestor).
- Shell: `simple.sc` → `quick.sc` in `FlowAuthoring`, `AuthorAction`,
  `AuthoringMenu`, `Cli`, and their tests (`FlowAuthoringTest`,
  `AuthorActionTest`, `AuthoringSandboxTest`, `FlowCatalogTest`,
  `BuiltInFlowsTest`).
- `runner/.../exports.scala`: export `Roadmap`, `Epic`, `ReviewReport`,
  `reviewOnce`; drop `Verdict`.
- `FlowCompilesTest`: replace the issue-pr / bugfix replicas and the
  `assessThenPlan` grid with `resolve.sc` / `epic.sc` / roadmap equivalents.
- `BuiltInFlowsCompileTest` compiles every shipped flow (unchanged mechanism).
- Unit tests: `TriageReply.toTriage` per case and malformed combinations;
  `roadmap` / `triage` grid conversions (`PlanGridTest`); `Roadmap#epicPrompt`;
  `reviewOnce` with stub reviewers (selection, attribution, no fix turn);
  `ReviewReport.render`; `prHandle` parsing and host resolution.
  `AssessThenPlanTest` and `BugTriageTest` are replaced.
- The test pinning flow fix-turn caps to `DefaultMaxFixTurns` covers the new
  call sites.
