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

case class PlanTask(branchName: String, description: String) derives JsonData
case class FlowPlan(tasks: List[PlanTask]) derives JsonData
case class BranchSlug(name: String) derives JsonData

object FlowCanary:

  /** Structured output via `derives JsonData` must be reachable through the
    * `resultAs[O]` path without any extra imports.
    */
  def structuredResult(): Unit =
    flow(OrcaArgs()):
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
    flow(OrcaArgs()):
      stage("impl"):
        val session = claude.newSession
        val _ = claude.autonomous.run("kick off", session)
        val _ = claude.autonomous.run("keep going", session)
        val _ = claude.autonomous.run("one-shot")

  /** Every top-level accessor must resolve from `import orca.*` alone.
    */
  def accessors(): Unit =
    flow(OrcaArgs()):
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
          LlmConfig.default.copy(model = Some(Model("gpt-5.5")))
        )

  /** Review-and-fix loop; pulls in `allReviewers` and the internal `stage`/fork
    * machinery.
    */
  def reviewLoop(): Unit =
    flow(OrcaArgs()):
      val (sessionId, plan) = stage("plan"):
        claude.resultAs[FlowPlan].interactive.run(userPrompt)
      for task <- plan.tasks do
        stage(task.description):
          reviewAndFixLoop(
            coder = claude,
            sessionId = sessionId,
            reviewers = allReviewers(claude),
            reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
            task = task.description,
            formatCommand = Some("mvn -q spotless:apply"),
            lintCommand = Some("mvn -q test"),
            lintLlm = Some(claude.haiku)
          )

  /** Config overrides must be reachable as unqualified names so users can write
    * `flow(args = ..., workDir = ...)` straight from `import orca.*`.
    */
  def configured(): Unit =
    flow(args = OrcaArgs("hello"), workDir = os.pwd):
      stage("cfg"):
        val _ = claude.autonomous.run(userPrompt)

  /** Typical scripted entry: parse the CLI argv and hand it straight to `flow`.
    * `args` here stands in for the scala-cli script's top-level `args:
    * Array[String]`.
    */
  def fromCliArgs(args: Array[String]): Unit =
    flow(OrcaArgs(args)):
      stage("start"):
        val _ = claude.autonomous.run(userPrompt)

  /** `summarisePr` + `PrSummary` surface; exercised by `examples/issue-pr.sc`.
    * Pins the call shape (`llm`, `diff`, optional `context`, optional
    * `instructions`) and the result type so a rename or signature drift
    * surfaces in this test instead of at the next live run.
    */
  def summarisePrSurface(): Unit =
    flow(OrcaArgs()):
      stage("pr"):
        val summary: PrSummary = summarisePr(
          llm = claude.haiku,
          diff = git.diff(),
          context = Some("Originating issue: acme/widgets#7")
        )
        val _ = summary.title
        val _ = summary.body

  /** Issue/PR-comment surface on `gh` — exercised by the issue-pr plan in
    * `examples/`. If any of these signatures move, the canary fails.
    */
  def issueAndPrSurface(): Unit =
    flow(OrcaArgs()):
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
    * the resumable-branch ops (`recover` guard, `ensureClean`,
    * `checkoutOrCreate`, `currentBranch`, `checkout`), the `createPr` `Either`
    * with its recoverable `PrAlreadyExists`, and the merge-base diff that feeds
    * `summarisePr`. A signature drift surfaces here instead of at the next live
    * run.
    */
  def branchAndPrSurface(): Unit =
    flow(OrcaArgs()):
      val planFile = Plan.defaultPath(userPrompt)
      val startBranch = git.currentBranch()
      if Plan.recover(planFile).isEmpty then
        val _ = git.ensureClean("orca: pre-implement stash")
        val branch =
          claude.haiku.resultAs[BranchSlug].autonomous.run(userPrompt)._2.name
        git.checkoutOrCreate(branch)
      stage("pr"):
        git.push().orThrow
        val summary = summarisePr(
          llm = claude.haiku,
          diff = git.diffVsBase(git.defaultBase())
        )
        gh.createPr(title = summary.title, body = summary.body) match
          case Left(_: PrAlreadyExists) => ()
          case Left(e)                  => throw e
          case Right(_)                 => ()
        git.checkout(startBranch).orThrow

  /** Planning grid surface; exercised across `examples/`. Pins the full `mode ×
    * operation` grid: every cell returns `Sessioned[B, <result>]` where the
    * result is `Plan` (`from`), `Verdict[Plan]` (`assessThenPlan`), or `Triage`
    * (`triage`). A hole in the grid, a return-type drift, or an enum
    * rename/case removal surfaces here instead of at the next live run.
    */
  def planningGridSurface(): Unit =
    flow(OrcaArgs()):
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

  /** Post-planning steps (`reviewed` / `briefed`) and the brief-aware
    * persistence surface — exercised by `examples/implement-enhanced.sc`. Pins
    * that the `Sessioned[B, Plan]` / `Sessioned[B, PlanWithBrief]` extensions
    * resolve through `import orca.*` alone (implicit scope = the `Plan` /
    * `PlanWithBrief` companions), and that both step orders type-check.
    */
  def planReviewAndBriefSurface(): Unit =
    flow(OrcaArgs()):
      stage("review+brief"):
        // review-then-brief and brief-then-review both yield a PlanWithBrief.
        val reviewedThenBriefed: Sessioned[?, PlanWithBrief] =
          Plan.autonomous
            .from(userPrompt, claude)
            .reviewed(claude)
            .briefed(claude)
        val briefedThenReviewed: Sessioned[?, PlanWithBrief] =
          Plan.autonomous
            .from(userPrompt, claude)
            .briefed(claude)
            .reviewed(claude)
        val _ = briefedThenReviewed
        // review alone stays a bare Plan.
        val reviewedOnly: Sessioned[?, Plan] =
          Plan.autonomous.from(userPrompt, claude).reviewed(claude)
        val _ = reviewedOnly

        val planFile = Plan.defaultPath(userPrompt)
        val plan: PlanLike =
          Plan.recoverOrCreate(planFile)(reviewedThenBriefed.value)
        Plan.implementTaskLoop(planFile, plan): task =>
          val _ =
            claude.autonomous.run(plan.taskPrompt(task), claude.newSession)
