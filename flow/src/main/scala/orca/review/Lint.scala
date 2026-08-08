package orca.review

// Capture-checked to match `ReviewLoop.scala`, where `lint` is fanned out
// alongside the reviewers through the CheckedPar funnel (ADR 0018).
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{FlowContext, InStage, OrcaDir}
import orca.agents.{Agent, Chat}

/** The lint gate run alongside the reviewers each round: `commands` (each run
  * via `bash -c`, in order) and the `agent` that summarises their concatenated
  * output into a `ReviewResult` (a cheap model suffices — the summary is a
  * small fold). Bundling the pair makes "commands with no summariser"
  * unrepresentable.
  *
  * The commands must not modify sources. They run concurrently with the
  * reviewers, against the change set sampled when the round started, so a
  * rewrite mid-round makes the reviewers review a moving target. Source
  * rewriting belongs to the format task, which `reviewAndFixLoop` runs
  * serialized before the round (ADR 0019).
  */
case class Lint(commands: List[String], agent: Agent[?])

/** Run `cmd` through `bash -c` in `ctx.workDir`. A nonzero exit is returned,
  * not thrown — the caller decides what a failure means. `mergeErrIntoOut`
  * folds stderr into the captured stdout so neither stream reaches the terminal
  * and tears the renderer's status row.
  */
private[review] def runShell(cmd: String)(using
    ctx: FlowContext
): os.CommandResult =
  os.proc("bash", "-c", cmd)
    .call(cwd = ctx.workDir, check = false, mergeErrIntoOut = true)

/** One executed lint command, rendered for the summariser as a labelled block
  * headed
  * {{{
  * $ <command>   (exit <status>)
  * }}}
  * with the trimmed output following. Empty output keeps the label line alone,
  * so the summariser still sees "ran, produced nothing, exited N".
  */
private case class LintRun(command: String, exitCode: Int, output: String):
  def labelled: String =
    val label = s"$$ $command   (exit $exitCode)"
    if output.isEmpty then label else s"$label\n$output"

/** Run each of `commands` via `bash -c`, in order, in `ctx.workDir`, capturing
  * each command's combined stdout+stderr; then ask `agent` to summarise the
  * concatenation as a `ReviewResult`. Every command runs even when an earlier
  * one fails, so a broken first linter doesn't hide the second's diagnostics.
  * When every command exits 0 with empty output (including `commands = Nil`)
  * the call short-circuits to `ReviewResult.empty`, skipping the LLM; a silent
  * nonzero exit still reaches the summariser. Override `instructions` for lint
  * output the default phrasing doesn't fit.
  *
  * Combined text ≤ [[Lint.InlineLintThreshold]] chars is inlined into the
  * prompt (the common case); larger output spills to a file the sandboxed agent
  * can reach, avoiding a context-window overflow.
  *
  * Each call summarises on a fresh read-only conversation (see
  * [[Lint.Summariser]]); to reuse one across calls, pass a summariser to the
  * overload below.
  */
def lint(
    commands: List[String],
    agent: Agent[?],
    instructions: String = ReviewLoopPrompts.SummariseLint
)(using ctx: FlowContext, ev: InStage): ReviewResult =
  lint(commands, Lint.summariser(agent), instructions).result

/** [[lint]] against an existing [[Lint.Summariser]] rather than a fresh
  * conversation per call — for a caller running the gate several times over one
  * stage, where resuming costs a fraction of re-establishing the session.
  *
  * Hands the summariser back only while the conversation is still safe to
  * resume, so a caller that carries the report's handle forward never resumes
  * one that has reported (see [[LintReport.resumableSummariser]]).
  */
def lint(
    commands: List[String],
    summariser: Lint.Summariser,
    instructions: String
)(using ctx: FlowContext, ev: InStage): LintReport =
  val runs = commands.map: command =>
    val proc = runShell(command)
    LintRun(command, proc.exitCode, proc.out.text().trim)
  // The summariser is skipped only when every run is both silent AND successful.
  val allClean = runs.forall(r => r.exitCode == 0 && r.output.isEmpty)
  val result =
    if allClean then ReviewResult.empty
    else summariseRuns(runs, summariser, instructions)
  // Pre-gate issues: what the conversation now holds, whatever a caller's
  // confidence gate later admits of it.
  LintReport(result, Option.when(result.issues.isEmpty)(summariser))

/** One [[lint]] call's outcome.
  *
  * `resumableSummariser` is the summariser that was passed in, handed back only
  * while the conversation holds no finding: one that has reported can repeat
  * that finding on a later call whose commands no longer show it, and the
  * phantom costs a fix turn and lands in the caller's records as unfixed. A
  * caller that carries this field forward rather than the handle it passed in
  * therefore starts a fresh conversation after any report.
  *
  * That bounds re-reporting rather than eliminating it: a resumed conversation
  * still holds every earlier call's raw output, from which it could newly
  * derive a finding it previously declined to make. Only the summariser prompt
  * (`the blocks are this run's output and supersede any earlier ones`) speaks
  * to that, and by construction the retained output is output the model already
  * judged non-actionable.
  */
case class LintReport(
    result: ReviewResult,
    resumableSummariser: Option[Lint.Summariser]
)

private def summariseRuns(
    runs: List[LintRun],
    summariser: Lint.Summariser,
    instructions: String
)(using ctx: FlowContext, ev: InStage): ReviewResult =
  def summarise(prompt: String): ReviewResult =
    summariser.chat
      .resultAs[ReviewResult]
      .autonomous
      .run(prompt, emitPrompt = false)
  val combined = runs.map(_.labelled).mkString("\n\n")
  val statusHint =
    "Each command's combined stdout+stderr is a block headed " +
      "`$ <command>   (exit <status>)`. A zero status usually means that " +
      "command succeeded with nothing to report — return an empty result " +
      "when no block carries anything actionable. The blocks are this run's " +
      "output and supersede any earlier ones"
  // No `stripMargin`: compiler diagnostics, tables and markdown in the
  // captured output start lines with `|`, which it would eat.
  val promptHead = s"$instructions\n\n$statusHint.\n\n"
  if combined.length <= Lint.InlineLintThreshold then
    summarise(promptHead + s"The blocks are:\n\n```\n$combined\n```")
  else
    // Spill to a file under the working tree (NOT `/tmp`, so a sandboxed
    // agent that denies reads outside its worktree can reach it).
    // `.orca/cache/` self-ignores via the `.gitignore` `ensureCache` writes
    // first, so a stage's `git add -A` can never sweep the spill file, even
    // after a crash mid-lint. `deleteOnExit = false`: the `finally` owns
    // cleanup, avoiding a JVM-exit hook per lint call.
    val cacheDir = OrcaDir.ensureCache(ctx.workDir)
    val outputFile =
      os.temp(
        combined,
        dir = cacheDir,
        prefix = "lint-",
        suffix = ".txt",
        deleteOnExit = false
      )
    try
      summarise(
        promptHead + s"The blocks are in `$outputFile`\n" +
          "(the file may be large — read it in parts if needed)."
      )
    finally
      val _ = os.remove(outputFile)

// Public: restricting the companion would restrict `Lint(...)` too.
object Lint:
  /** A conversation [[lint]] may summarise into, always read-only: it may
    * verify a lint claim against the sources it references, but must not edit.
    * The `private[review]` constructor makes [[summariser]] the only way to
    * obtain one, so handing `lint` a write-enabled conversation is
    * unrepresentable.
    */
  final class Summariser private[review] (
      private[review] val chat: Chat[?]
  )

  /** A fresh [[Summariser]] over `agent`. */
  def summariser(agent: Agent[?]): Summariser =
    new Summariser(agent.withReadOnly.chat())

  /** Max combined lint-output length (chars) inlined into the summariser
    * prompt; larger output spills to a file (see [[lint]]). Sized so a typical
    * lint failure inlines — keeping the gate working under sandboxed agents
    * that can't read outside their worktree — while a full build/test dump goes
    * to a file rather than flooding the context.
    */
  private[review] val InlineLintThreshold: Int = 8 * 1024
