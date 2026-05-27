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

case class PlanTask(branchName: String, description: String) derives JsonData
case class FlowPlan(tasks: List[PlanTask]) derives JsonData

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

  /** Issue/PR-comment surface on `gh` — exercised by the issue-pr plan in
    * `plans/`. If any of these signatures move, the canary fails.
    */
  def issueAndPrSurface(): Unit =
    flow(OrcaArgs()):
      stage("gh"):
        val issueHandle = IssueHandle("acme", "widgets", 7)
        val issue: Issue = gh.readIssue(issueHandle)
        val _ = issue.title
        val _ = issue.body
        val _ = gh.readIssueComments(issueHandle)
        gh.writeComment(issueHandle, "follow-up question")
        val pr = PrHandle("acme", "widgets", 7)
        val _ = gh.readPrComments(pr)
        gh.writeComment(pr, "pr comment")

  /** Assess-then-plan + Verdict surface; exercised by `plans/issue-pr.sc`. Pins
    * the type-level shape (`Verdict[Plan]` is sum-matchable; `RejectionKind` is an
    * enum with these three cases) so script changes — or an enum rename/case
    * removal — can't silently bypass the assess gate.
    */
  def assessThenPlanSurface(): Unit =
    flow(OrcaArgs()):
      stage("assess"):
        // Pin both return-type contracts: assessThenPlan → Verdict[Plan],
        // and Plan.{autonomous,interactive}.from → Plan (no SessionId pair).
        val autoPlan: Plan = Plan.autonomous.from(userPrompt, claude.opus)
        val intPlan: Plan = Plan.interactive.from(userPrompt, claude)
        // Codex now also satisfies `CanAskUser`, so `Plan.interactive.from`
        // compiles against it too (the agent uses the same shared
        // AskUserMcpServer via codex's MCP support).
        val intPlanCodex: Plan = Plan.interactive.from(userPrompt, codex)
        val _ = autoPlan
        val _ = intPlan
        val _ = intPlanCodex
        val verdict =
          Plan.autonomous.assessThenPlan(userPrompt, claude.opus)
        verdict match
          case Verdict.Proceed(_) => ()
          case Verdict.Rejection(Verdict.RejectionKind.Question, _)   => ()
          case Verdict.Rejection(Verdict.RejectionKind.Critique, _)   => ()
          case Verdict.Rejection(Verdict.RejectionKind.Rebuff, _)     => ()
