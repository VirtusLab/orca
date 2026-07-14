package flowtests

// This file deliberately lives outside the `orca.*` package tree — it's
// the canary for the user-facing DSL. A third-party flow script sees
// exactly what this file sees: top-level types, accessors, and givens
// brought in by `import orca.{*, given}` and nothing else. The `given`
// selector is non-negotiable: Scala 3's plain `import orca.*` excludes
// givens, and the forwarders in `JsonData.scala` are what let nested
// `derives JsonData` find the child Schema during derivation.
//
// Each `def` targets one DSL concern so compile errors localise to the
// affected surface. Nothing here is invoked at runtime; `sbt test`
// simply requires the file to typecheck.
//
// If this file stops compiling, some aspect of the DSL contract has
// regressed. Fix the API, not the test.

import orca.{*, given}
// Deliberately NOT in the `orca.*` export wildcard: a recoverable `createPr`
// failure, referenced by name in `examples/implement-enhanced.sc`. Pinning it
// here keeps the "import it explicitly" requirement honest.
import orca.tools.PrAlreadyExists
import orca.agents.AgentConfig

case class PlanTask(branchName: String, description: String) derives JsonData
case class FlowPlan(tasks: List[PlanTask]) derives JsonData
case class BranchSlug(name: String) derives JsonData

object FlowCanary:

  // The leading model is named by a `flow(...)` selector resolved against the
  // flow context (ADR 0018 §2.5). A positional `agent` selector is
  // required: `_.claude`, `_.codex`, etc. These canaries use the real shapes
  // the `examples/*.sc` files use — no hand-built stub.

  /** Structured output via `derives JsonData` must be reachable through the
    * `resultAs[O]` path without any extra imports.
    */
  def structuredResult(): Unit =
    flow(OrcaArgs(), _.claude):
      // Durable structured turns go through the FlowSession door (seeded,
      // persisted); the raw `resultAs[O]` door is exercised with ephemeral
      // (fresh / `.id`) sessions only — never a durable-session id. Minting
      // must sit outside the stage (OutsideStage rejects it inside).
      val session = claude.session("plan", seed = userPrompt)
      stage("plan"):
        val _ = session.resultAs[FlowPlan].run(userPrompt)
        val _ = session.resultAs[FlowPlan].run("follow up")
        // Interactive is deliberately ephemeral-only (see FlowSession): a
        // one-shot on the agent, or a continuation on a Chat — including the
        // `agent.chat(session.id)` escape hatch over a durable session.
        val _ = claude.resultAs[FlowPlan].interactive.run(userPrompt)
        val _ =
          claude.chat(session.id).resultAs[FlowPlan].interactive.run("refine")

  /** Free-form text prompts and session continuation; the shape the README
    * promises for per-task implementation.
    */
  def continuedSession(): Unit =
    flow(OrcaArgs(), _.claude):
      // Durable free-text continuation goes through the FlowSession door.
      val session = claude.session("impl", seed = userPrompt)
      stage("impl"):
        val _ = session.run("kick off")
        val _ = session.run("keep going")
        // A bare ephemeral one-shot and a multi-turn Chat sit alongside it.
        val _ = claude.run("one-shot")
        val chat: Chat[?] = claude.chat()
        val _ = chat.run("kick off")
        val _ = chat.run("keep going")

  /** Every top-level accessor must resolve from `import orca.*` alone.
    */
  def accessors(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("tools"):
        val _ = git.createBranch("x")
        val _ = git.commit("msg")
        val _ = gh
        val _ = fs
        val _ = codex
        val _ = userPrompt
        // Per-tool config knobs resolve and chain on both backends.
        val _ = claude.withReadOnly.withSelfManagedGit
        val _ = codex.withSelfManagedGit
        val _ = pi.withConfig(
          AgentConfig().copy(model = Some(Model("gpt-5.5")))
        )

  /** Review-and-fix loop; pulls in `allReviewers` and the internal `display`/
    * fork machinery (which now runs under the caller's stage).
    */
  def reviewLoop(): Unit =
    flow(OrcaArgs(), _.claude):
      // Named, durable, rehydratable coder session (has a SessionRecord, so a
      // resume can rehydrate it) — NOT a session id carried as a stage result.
      val session = claude.session("plan", seed = userPrompt)
      // The planning turn is interactive, which FlowSession deliberately does
      // not offer (see the FlowSession scaladoc); run it on a Chat adopting
      // the session id (`agent.chat(session.id)`). The stage persists ONLY
      // the FlowPlan.
      val plan: FlowPlan = stage("plan"):
        claude.chat(session.id).resultAs[FlowPlan].interactive.run(userPrompt)
      for task <- plan.tasks do
        stage(task.description):
          reviewAndFixLoop(
            coderSession = session,
            reviewers = allReviewers(claude),
            reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
            task = task.description,
            formatCommand = Some("mvn -q spotless:apply"),
            lint = Some(Lint("mvn -q test", claude.haiku))
          )

  /** User-authored parallel fan-out: `Par.mapUnordered` plus per-fork ephemeral
    * [[Chat]]s — the sanctioned shape for a hand-rolled review loop. The
    * durable `coder.run` stays on the flow thread; each fork mints and resumes
    * its own chat (`InStage` crosses the fork; `WorkspaceWrite` and the
    * FlowSession doors must not).
    */
  def customFanOutSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      val coder = claude.session("implementer", seed = userPrompt)
      stage("review"):
        val _ = coder.run("implement the task")
        val reviewers = List(claude.sonnet.withReadOnly, codex.withReadOnly)
        val chats: List[Chat[?]] = Par.mapUnordered(2)(reviewers): r =>
          val c = r.chat()
          val _ = c.run("review the diff")
          c
        val _ = coder.run("fix what the reviewers found")
        val _ = Par.mapUnordered(2)(chats): c =>
          c.run("re-review after the fixes")
        // The escape hatch: continue the durable conversation ephemerally
        // (e.g. from a fork) — turns here are not persisted.
        val _ = claude.chat(coder.id).run("one unpersisted follow-up")

  /** A custom [[ReviewerSelector]] must be implementable from `import orca.*`
    * alone: its `prepare` is handed the roster as opaque `RosterEntry` handles
    * and returns a subset/permutation of them (a foreign agent is
    * unrepresentable). Pins that `RosterEntry`, `ReviewBatch`, and the trait's
    * roster-bound signature all resolve through the public export surface.
    */
  def customReviewerSelectorSurface(): Unit =
    val _: ReviewerSelector = new ReviewerSelector:
      def prepare(
          all: List[RosterEntry[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, InStage) =
        (_: List[ReviewBatch]) => all.reverse

  /** The reviewer-customisation surface must be reachable from `import orca.*`
    * alone: `Reviewer`, `ReviewerPrompts` (the shipped set + its preset lists),
    * and `buildReviewers` to turn a composed `List[Reviewer]` into the agents
    * `reviewAndFixLoop` takes. Pins that a flow can swap or extend the reviewer
    * set without any side import.
    */
  def reviewerCustomisationSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("reviewers"):
        val custom: Reviewer = Reviewer(
          name = "my-thing",
          description = "checks my thing",
          systemPrompt = "…"
        )
        val list: List[Reviewer] = ReviewerPrompts.minimal :+ custom
        val _: List[Agent[?]] = buildReviewers(claude, list)
        val _: List[Agent[?]] = allReviewers(claude)
        val _: Map[String, String] = ReviewerPrompts.descriptionsBySlug

  /** Config overrides must be reachable as unqualified names so users can write
    * `flow(args = ..., workDir = ...)` straight from `import orca.*`.
    */
  def configured(): Unit =
    flow(args = OrcaArgs("hello"), agent = _.claude, workDir = os.pwd):
      stage("cfg"):
        val _ = claude.run(userPrompt)

  /** Typical scripted entry: parse the CLI argv and hand it straight to `flow`.
    * `args` here stands in for the scala-cli script's top-level `args:
    * Array[String]`.
    */
  def fromCliArgs(args: Array[String]): Unit =
    flow(OrcaArgs(args), _.claude):
      stage("start"):
        val _ = claude.run(userPrompt)

  /** `summarisePr` + `PrSummary` surface; exercised by `examples/issue-pr.sc`.
    * Pins the call shape (`agent`, `diff`, optional `context`, optional
    * `instructions`) and the result type so a rename or signature drift
    * surfaces in this test instead of at the next live run.
    */
  def summarisePrSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("pr"):
        val summary: PrSummary = summarisePr(
          agent = claude.haiku,
          diff = git.diff(),
          context = Some("Originating issue: acme/widgets#7")
        )
        val _ = summary.title
        val _ = summary.body

  /** 10.3: types newly added to `exports.scala` (`Usage`, `Cost`,
    * `CostTracker`, `IgnoredIssue`/`IgnoredIssues`, `PushFailure`) must resolve
    * from `import orca.*` alone, with no side import.
    */
  def exportsSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("exports"):
        val tracker = new CostTracker()
        val _: Option[Cost] = tracker.totalCost
        val listener: OrcaListener =
          case OrcaEvent.TokensUsed(_, _, usage, _) =>
            val _: Usage = usage
          case _ => ()
        val _ = listener
        val ignored: IgnoredIssues = fixLoop(
          evaluate = () => ReviewResult.empty,
          fix = _ => FixOutcome(fixed = Nil, ignored = Nil)
        )
        val _: List[IgnoredIssue] = ignored.issues
        git.push() match
          case Left(_: PushFailure.NonFastForward) => ()
          case Left(_: PushFailure.RemoteDeclined) => ()
          case Right(_)                            => ()

  /** Issue/PR-comment surface on `gh` — exercised by the issue-pr plan in
    * `examples/`. If any of these signatures move, the canary fails.
    */
  def issueAndPrSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("gh"):
        val issueHandle = IssueHandle.parseOrThrow("acme/widgets#7")
        val _: Either[String, IssueHandle] = IssueHandle.parse("acme/widgets#7")
        val issue: Issue = gh.readIssue(issueHandle)
        val _ = issue.title
        val _ = issue.body
        val _ = gh.readIssueComments(issueHandle)
        gh.writeComment(issueHandle, "follow-up question")
        gh.upsertComment(
          issueHandle,
          orcaCommentMarker(userPrompt, "reject"),
          "updated verdict"
        )
        val pr = PrHandle("acme", "widgets", 7)
        val _ = gh.readPrComments(pr)
        gh.writeComment(pr, "pr comment")
        gh.updatePr(pr, "new title", "new body")

  /** Branch + PR surface — exercised by `examples/implement-enhanced.sc`. Pins
    * the branch ops the runtime still exposes to flow scripts and the
    * `createPr` `Either` with its recoverable `PrAlreadyExists`. The manual
    * `Plan.recover`/`ensureClean`/`checkoutOrCreate` resume guard is gone — the
    * flow runtime now owns branch + resume (ADR 0018 §2.5); the examples'
    * conversion restored the per-flow branching ceremony.
    */
  def branchAndPrSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("pr"):
        git.push().orThrow
        val summary = summarisePr(
          agent = claude.haiku,
          diff = git.diffVsBase(git.defaultBase())
        )
        gh.createPr(title = summary.title, body = summary.body) match
          case Left(_: PrAlreadyExists) => ()
          case Left(e)                  => throw e
          case Right(_)                 => ()

  /** Planning grid surface; exercised across `examples/`. Pins the full `mode ×
    * operation` grid: every cell returns `Sessioned[B, <result>]` where the
    * result is `Plan` (`from`), `Verdict[Plan]` (`assessThenPlan`), or `Triage`
    * (`triage`). A hole in the grid, a return-type drift, or an enum
    * rename/case removal surfaces here instead of at the next live run.
    */
  def planningGridSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("grid"):
        // --- from → Sessioned[B, Plan], both modes ---
        val autoFrom: Sessioned[?, Plan] =
          Plan.autonomous.from(userPrompt, claude.opus)
        val intFrom: Sessioned[?, Plan] =
          Plan.interactive.from(userPrompt, claude)
        // Codex and Pi also satisfy `CanAskUser`, so the interactive cells
        // compile against them too.
        val intFromCodex: Sessioned[?, Plan] =
          Plan.interactive.from(userPrompt, codex)
        val intFromPi: Sessioned[?, Plan] =
          Plan.interactive.from(userPrompt, pi)
        val _ = (
          autoFrom.value,
          intFrom.value,
          intFromCodex.value,
          intFromPi.value
        )

        // --- assessThenPlan → Sessioned[B, Verdict[Plan]], both modes ---
        val autoAssess: Sessioned[?, Verdict[Plan]] =
          Plan.autonomous.assessThenPlan(userPrompt, claude.opus)
        val intAssess: Sessioned[?, Verdict[Plan]] =
          Plan.interactive.assessThenPlan(userPrompt, claude)
        val _ = intAssess
        autoAssess.value match
          case Verdict.Proceed(_)                                   => ()
          case Verdict.Rejection(Verdict.RejectionKind.Question, _) => ()
          case Verdict.Rejection(Verdict.RejectionKind.Critique, _) => ()
          case Verdict.Rejection(Verdict.RejectionKind.Rebuff, _)   => ()

        // --- triage → Sessioned[B, Triage], both modes ---
        val autoTriage: Sessioned[?, Triage] =
          Plan.autonomous.triage(userPrompt, claude.opus)
        val _ = autoTriage.value
        // Destructure the concretely-typed interactive result, as the bugfix
        // plan does (`val Sessioned(session, triage) = ...`).
        val Sessioned(_, triage) = Plan.interactive.triage(userPrompt, claude)
        triage match
          case Triage.NotABug(_)        => ()
          case Triage.Untestable(_, _)  => ()
          case Triage.Testable(_, _, _) => ()

  /** Post-planning step (`reviewed`) plus the per-task stage loop — exercised
    * by `examples/implement-enhanced.sc`. Pins that the `Sessioned[B, Plan]`
    * extension resolves through `import orca.*` alone. Plans are always briefed
    * (the `brief` rides in the structured output, so `plan.brief` /
    * `plan.taskPrompt` are always available — no `.briefed` step, no
    * `PlanWithBrief`). The `Plan.recoverOrCreate` / `implementTaskLoop`
    * persistence calls are gone — resume is now the stage log (ADR 0018 §2.8),
    * and the task loop is a plain per-task `stage(...)`.
    */
  def planReviewAndBriefSurface(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan =
        stage("plan"):
          Plan.autonomous
            .from(userPrompt, claude)
            .reviewed(claude)
            .value

      for task <- plan.tasks do
        stage(s"task: ${task.title.value}"):
          // omitting the session arg gives a fresh one-shot session
          val _ = claude.run(plan.taskPrompt(task))

  // -----------------------------------------------------------------------
  // Example-shape canaries (ADR 0018 §3, Task F2)
  // Each def mirrors the distinct pattern in one of the `examples/*.sc`
  // files so a signature drift or missing API surfaces here instead of at
  // the next live run. Nothing is invoked at runtime.
  // -----------------------------------------------------------------------

  /** `implement.sc`: autonomous plan → session seeded from brief → task loop
    * with `session.run` + `reviewAndFixLoop`. The session-based shapes
    * (`session(name, seed=)` → `FlowSession`, `session.run`) are the core
    * new-API additions.
    */
  def implementFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.autonomous.from(userPrompt, claude).value

      val session = claude.session("implementer", seed = plan.brief)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = session.run(task.description)
          // reviewerSelection omitted: defaults to agentDriven(claude.cheap).
          reviewAndFixLoop(
            coderSession = session,
            reviewers = allReviewers(claude),
            task = task.title.value,
            formatCommand = Some("cargo fmt"),
            lint = Some(Lint("cargo check --tests", claude.haiku))
          )

  /** `implement-interactive.sc`: interactive plan → session → task loop. Only
    * the planning call differs from `implementFlowShape`.
    */
  def interactivePlanFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.interactive.from(userPrompt, claude).value

      val session = claude.session("implementer", seed = plan.brief)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = session.run(task.description)
          reviewAndFixLoop(
            coderSession = session,
            reviewers = allReviewers(claude),
            task = task.title.value,
            formatCommand = Some("cargo fmt")
          )

  /** `implement-enhanced.sc`: plan → `.reviewed` → the seeded implementer
    * session → task loop with `taskPrompt` → `openPrFromBranch`.
    */
  def enhancedImplementFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.autonomous.from(userPrompt, claude).reviewed(claude).value

      val session = claude.session("implementer", seed = plan.brief)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = session.run(plan.taskPrompt(task))
          reviewAndFixLoop(
            coderSession = session,
            reviewers = allReviewers(claude),
            task = task.title.value,
            formatCommand = Some("cargo fmt")
          )

      val _ = openPrFromBranch(summarisingAgent = claude.haiku)

  /** The leading-model selector resolves against the flow context (ADR 0018
    * §2.5): a positional `agent` selector is required (`_.claude`, `_.codex`,
    * …). Pins the `_.codex` positional shape so a selector regression surfaces
    * here.
    */
  def agentSelector(): Unit =
    flow(OrcaArgs(), _.codex):
      stage("lead"):
        val _ = codex.run(userPrompt)

  /** `epic.sc`: cross-backend review — claude implements, codex reviews.
    * Exercises the `allReviewers(codex)` shape and `claude.opus` planning.
    */
  def epicFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.autonomous.from(userPrompt, claude.opus).value

      val session = claude.session("implementer", seed = plan.brief)
      val reviewers: List[Agent[?]] = allReviewers(codex)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = session.run(task.description)
          reviewAndFixLoop(
            coderSession = session,
            reviewers = reviewers,
            task = task.title.value,
            formatCommand = Some("mvn -q spotless:apply")
          )

      stage("Update documentation"):
        val _ = session.run(
          "Update project docs based on the changes made."
        )

  /** `issue-pr.sc`: read issue outside stage, `assessThenPlan`, optional plan,
    * session from plan brief, task loop, push, PR. Also exercises
    * `BranchNamingStrategy.issue` and the `Verdict` match.
    */
  def issuePrFlowShape(): Unit =
    val orcaArgs = OrcaArgs("acme/widgets#42")
    val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)
    flow(
      orcaArgs,
      _.claude,
      branchNaming = Some(BranchNamingStrategy.issue(issueHandle))
    ):
      // Read outside stage (no InStage needed).
      val issue: Issue = gh.readIssue(issueHandle)

      val (maybePlan, rejectionBody) = stage("Assess and plan"):
        Plan.autonomous.assessThenPlan(issue.body, claude.opus).value match
          case Verdict.Rejection(_, body) => (None: Option[Plan], body)
          case Verdict.Proceed(plan)      => (Some(plan), "")

      if maybePlan.isEmpty then
        stage("Comment: rejection"):
          gh.writeComment(issueHandle, rejectionBody)

      maybePlan.foreach: plan =>
        val session = claude.session("implementer", seed = plan.brief)

        for task <- plan.tasks do
          stage(s"task: ${task.title}"):
            val _ = session.run(task.description)
            reviewAndFixLoop(
              coderSession = session,
              reviewers = allReviewers(claude),
              task = task.title.value,
              formatCommand = Some("npx prettier --write .")
            )

        val _ = openPrFromBranch(
          summarisingAgent = claude.haiku,
          body =
            summary => s"${summary.body}\n\nCloses ${issueHandle.shortRef}."
        )

  /** `issue-pr-bugfix.sc`: the push-after-edit authoring rule (ADR 0018).
    * "Write failing test" commits the test; a LATER "Push + open PR" stage
    * pushes it. Also covers `triage`, `waitForBuild` outside a stage,
    * `session.run` in a nested helper, and the final push+updatePr stage.
    */
  def bugfixFlowShape(): Unit =
    import scala.concurrent.duration.DurationInt
    val orcaArgs = OrcaArgs("acme/widgets#42")
    val issueHandle = IssueHandle.parseOrThrow(orcaArgs.userPrompt)
    flow(
      orcaArgs,
      _.claude,
      branchNaming = Some(BranchNamingStrategy.issue(issueHandle))
    ):
      // Pure read outside any stage.
      val issue: Issue = gh.readIssue(issueHandle)

      val session = claude.session("fixer", seed = issue.body)

      val triage: Triage = stage("Triage"):
        Plan.autonomous.triage(issue.body, claude).value

      triage match
        case Triage.NotABug(explanation) =>
          stage("Comment: not a bug"):
            gh.writeComment(issueHandle, explanation)

        case Triage.Untestable(_, steps) =>
          stage("Comment: repro steps"):
            gh.writeComment(issueHandle, steps)

        case Triage.Testable(summary, _, failingTestPath) =>
          // Stage 1: write + commit the test.
          stage("Write failing test"):
            val _ = session.run(
              s"Write the failing test at $failingTestPath."
            )

          // Stage 2: LATER stage — push the already-committed test, open PR.
          // (Authoring rule R8: push must be in a later stage than the edit.)
          val pr: PrHandle = stage("Push + open tentative PR"):
            git.push().orThrow
            gh.createPr(title = summary, body = "Failing test only.").orThrow

          // `waitForBuild` is a pure polling read — outside any stage.
          if gh
              .waitForBuild(pr, 30.minutes)
              .orThrow
              .outcome == BuildOutcome.Success
          then
            fail(
              "CI passed on the failing-test commit — reproduction is wrong."
            )
          display(s"CI red on ${pr.shortRef} — confirmed")

          // Implement the fix in per-task stages.
          val fixPlan: Plan = stage("Plan the fix"):
            Plan.autonomous
              .from(s"Fix ${issueHandle.shortRef}", claude)
              .reviewed(claude)
              .value
          for task <- fixPlan.tasks do
            stage(s"task: ${task.title}"):
              val _ = session.run(fixPlan.taskPrompt(task))
              reviewAndFixLoop(
                coderSession = session,
                reviewers = allReviewers(claude),
                task = task.title.value,
                formatCommand = Some("sbt scalafmtAll"),
                lint = Some(Lint("sbt Test/compile", claude.haiku))
              )

          // Stage: push fix + finalise PR (later than the fix-task stages).
          stage("Push fix + finalise PR"):
            git.push().orThrow
            gh.updatePr(
              pr,
              title = "fix: " + summary,
              body = "Failing test + fix."
            )

object StackSettingsCanary:
  /** `StackSettings` (ADR 0019) is part of the script surface: it must resolve
    * through the same `import orca.{*, given}` wildcard as the flow DSL — it
    * lives at top-level `orca`, so no `exports.scala` entry carries it.
    */
  val settings: StackSettings = StackSettings.empty
