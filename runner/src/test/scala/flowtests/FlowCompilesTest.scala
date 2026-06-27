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
      stage("plan"):
        val session = claude.newSession
        val _ = claude.resultAs[FlowPlan].interactive.run(userPrompt, session)
        val _ = claude.resultAs[FlowPlan].interactive.run("refine", session)
        val _ = claude.resultAs[FlowPlan].autonomous.run(userPrompt, session)
        val _ = claude.resultAs[FlowPlan].autonomous.run("follow up", session)

  /** Free-form text prompts and session continuation; the shape the README
    * promises for per-task implementation.
    */
  def continuedSession(): Unit =
    flow(OrcaArgs(), _.claude):
      stage("impl"):
        val session = claude.newSession
        val _ = claude.autonomous.run("kick off", session)
        val _ = claude.autonomous.run("keep going", session)
        val _ = claude.autonomous.run("one-shot")

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
          AgentConfig.default.copy(model = Some(Model("gpt-5.5")))
        )

  /** Review-and-fix loop; pulls in `allReviewers` and the internal `display`/
    * fork machinery (which now runs under the caller's stage).
    */
  def reviewLoop(): Unit =
    flow(OrcaArgs(), _.claude):
      val (sessionId, plan) = stage("plan"):
        claude.resultAs[FlowPlan].interactive.run(userPrompt)
      for task <- plan.tasks do
        stage(task.description):
          reviewAndFixLoop(
            coder = claude,
            sessionId = sessionId,
            reviewers = allReviewers(claude),
            reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
            task = task.description,
            formatCommand = Some("mvn -q spotless:apply"),
            lintCommand = Some("mvn -q test"),
            lintAgent = Some(claude.haiku)
          )

  /** Config overrides must be reachable as unqualified names so users can write
    * `flow(args = ..., workDir = ...)` straight from `import orca.*`.
    */
  def configured(): Unit =
    flow(args = OrcaArgs("hello"), agent = _.claude, workDir = os.pwd):
      stage("cfg"):
        val _ = claude.autonomous.run(userPrompt)

  /** Typical scripted entry: parse the CLI argv and hand it straight to `flow`.
    * `args` here stands in for the scala-cli script's top-level `args:
    * Array[String]`.
    */
  def fromCliArgs(args: Array[String]): Unit =
    flow(OrcaArgs(args), _.claude):
      stage("start"):
        val _ = claude.autonomous.run(userPrompt)

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
        val pr = PrHandle("acme", "widgets", 7)
        val _ = gh.readPrComments(pr)
        gh.writeComment(pr, "pr comment")
        gh.updatePr(pr, "new title", "new body")

  /** Branch + PR surface — exercised by `examples/implement-enhanced.sc`. Pins
    * the branch ops the runtime still exposes to flow scripts and the
    * `createPr` `Either` with its recoverable `PrAlreadyExists`. The manual
    * `Plan.recover`/`ensureClean`/`checkoutOrCreate` resume guard is gone — the
    * flow runtime now owns branch + resume (ADR 0018 §2.5); the per-flow
    * branching ceremony is restored in Epic F's example conversion.
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
          val _ =
            claude.autonomous.run(plan.taskPrompt(task), claude.newSession)

  // -----------------------------------------------------------------------
  // Example-shape canaries (ADR 0018 §3, Task F2)
  // Each def mirrors the distinct pattern in one of the `examples/*.sc`
  // files so a signature drift or missing API surfaces here instead of at
  // the next live run. Nothing is invoked at runtime.
  // -----------------------------------------------------------------------

  /** `implement.sc`: autonomous plan → session seeded from brief → task loop
    * with `runSeeded` + `reviewAndFixLoop`. The session-based shapes
    * (`session(seed=)`, `runSeeded`) are the core new-API additions.
    */
  def implementFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.autonomous.from(userPrompt, claude).value

      val session = claude.session(seed = plan.brief)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = claude.runSeeded(task.description, session)
          reviewAndFixLoop(
            coder = claude,
            sessionId = session,
            reviewers = allReviewers(claude),
            reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
            task = task.title.value,
            formatCommand = Some("cargo fmt"),
            lintCommand = Some("cargo check --tests"),
            lintAgent = Some(claude.haiku)
          )

  /** `implement-interactive.sc`: interactive plan → session → task loop. Only
    * the planning call differs from `implementFlowShape`.
    */
  def interactivePlanFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.interactive.from(userPrompt, claude).value

      val session = claude.session(seed = plan.brief)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = claude.runSeeded(task.description, session)
          reviewAndFixLoop(
            coder = claude,
            sessionId = session,
            reviewers = allReviewers(claude),
            reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
            task = task.title.value,
            formatCommand = Some("cargo fmt")
          )

  /** `implement-enhanced.sc`: plan → `.reviewed` → session seeded from brief →
    * task loop with `taskPrompt` → push → PR via `createPr`.
    */
  def enhancedImplementFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.autonomous.from(userPrompt, claude).reviewed(claude).value

      val session = claude.session(seed = plan.brief)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = claude.runSeeded(plan.taskPrompt(task), session)
          reviewAndFixLoop(
            coder = claude,
            sessionId = session,
            reviewers = allReviewers(claude),
            reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
            task = task.title.value,
            formatCommand = Some("cargo fmt")
          )

      stage("Push branch"):
        git.push().orThrow

      val prSum = stage("Generate PR title and description"):
        summarisePr(
          agent = claude.haiku,
          diff = git.diffVsBase(git.defaultBase())
        )

      val _ = stage("Open PR"):
        gh.createPr(title = prSum.title, body = prSum.body).orThrow

  /** The leading-model selector resolves against the flow context (ADR 0018
    * §2.5): a positional `agent` selector is required (`_.claude`, `_.codex`,
    * …). Pins the `_.codex` positional shape so a selector regression surfaces
    * here.
    */
  def agentSelector(): Unit =
    flow(OrcaArgs(), _.codex):
      stage("lead"):
        val _ = codex.autonomous.run(userPrompt)

  /** `epic.sc`: cross-backend review — claude implements, codex reviews.
    * Exercises the `allReviewers(codex)` shape and `claude.opus` planning.
    */
  def epicFlowShape(): Unit =
    flow(OrcaArgs(), _.claude):
      val plan: Plan = stage("Plan"):
        Plan.autonomous.from(userPrompt, claude.opus).value

      val session = claude.session(seed = plan.brief)
      val reviewers: List[Agent[?]] = allReviewers(codex)

      for task <- plan.tasks do
        stage(s"task: ${task.title}"):
          val _ = claude.runSeeded(task.description, session)
          reviewAndFixLoop(
            coder = claude,
            sessionId = session,
            reviewers = reviewers,
            reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
            task = task.title.value,
            formatCommand = Some("mvn -q spotless:apply")
          )

      stage("Update documentation"):
        val _ = claude.runSeeded(
          "Update project docs based on the changes made.",
          session
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
        val session = claude.session(seed = plan.brief)

        for task <- plan.tasks do
          stage(s"task: ${task.title}"):
            val _ = claude.runSeeded(task.description, session)
            reviewAndFixLoop(
              coder = claude,
              sessionId = session,
              reviewers = allReviewers(claude),
              reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
              task = task.title.value,
              formatCommand = Some("npx prettier --write .")
            )

        stage("Push branch"):
          git.push().orThrow

        val _ = stage("Open PR"):
          gh.createPr(title = "fix", body = s"Closes ${issueHandle.shortRef}.")
            .orThrow

  /** `issue-pr-bugfix.sc`: the push-after-edit authoring rule (ADR 0018).
    * "Write failing test" commits the test; a LATER "Push + open PR" stage
    * pushes it. Also covers `triage`, `waitForBuild` outside a stage,
    * `claude.runSeeded` in a nested helper, and the final push+updatePr stage.
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

      val session = claude.session(seed = issue.body)

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
            val _ = claude.runSeeded(
              s"Write the failing test at $failingTestPath.",
              session
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
              val _ = claude.runSeeded(fixPlan.taskPrompt(task), session)
              reviewAndFixLoop(
                coder = claude,
                sessionId = session,
                reviewers = allReviewers(claude),
                reviewerSelection = ReviewerSelector.agentDriven(claude.haiku),
                task = task.title.value,
                formatCommand = Some("sbt scalafmtAll"),
                lintCommand = Some("sbt Test/compile"),
                lintAgent = Some(claude.haiku)
              )

          // Stage: push fix + finalise PR (later than the fix-task stages).
          stage("Push fix + finalise PR"):
            git.push().orThrow
            gh.updatePr(
              pr,
              title = "fix: " + summary,
              body = "Failing test + fix."
            )
