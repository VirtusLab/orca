package orca.review

// Capture-checked to match `ReviewLoop.scala`, where `lint` is fanned out
// alongside the reviewers through the CheckedPar funnel (ADR 0018).
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{FlowContext, InStage, OrcaDir}
import orca.agents.Agent

/** The lint gate run alongside the reviewers each round: `commands` (each run
  * via `bash -c`, in order) and the `agent` that summarises their concatenated
  * output into a `ReviewResult` (a cheap model suffices — the summary is a
  * small fold). Bundling the pair makes "commands with no summariser"
  * unrepresentable.
  */
case class Lint(commands: List[String], agent: Agent[?])

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
  * prompt (the common case), which is what makes lint work under a sandboxed
  * autonomous agent that denies file reads outside its worktree. Larger output
  * is spilled to a file under `<workDir>/.orca/cache/` (NOT `/tmp`, so an
  * in-sandbox worktree can still reach it), avoiding a context-window overflow.
  *
  * The LLM is invoked read-only: the agent may verify a lint claim against the
  * sources it references but must not edit.
  */
def lint(
    commands: List[String],
    agent: Agent[?],
    instructions: String = ReviewLoopPrompts.SummariseLint
)(using ctx: FlowContext, ev: InStage): ReviewResult =
  val runs = commands.map: command =>
    val proc = os
      .proc("bash", "-c", command)
      .call(cwd = ctx.workDir, check = false, mergeErrIntoOut = true)
    LintRun(command, proc.exitCode, proc.out.text().trim)
  // The summariser is skipped only when every run is both silent AND successful.
  val allClean = runs.forall(r => r.exitCode == 0 && r.output.isEmpty)
  if allClean then ReviewResult.empty
  else
    def summarise(prompt: String): ReviewResult =
      agent.withReadOnly
        .resultAs[ReviewResult]
        .autonomous
        .run(prompt, emitPrompt = false)
    val combined = runs.map(_.labelled).mkString("\n\n")
    val statusHint =
      "Each command's combined stdout+stderr is a block headed " +
        "`$ <command>   (exit <status>)`. A zero status usually means that " +
        "command succeeded with nothing to report — return an empty result " +
        "when no block carries anything actionable"
    if combined.length <= Lint.InlineLintThreshold then
      summarise(
        s"""$instructions
           |
           |$statusHint.
           |
           |The blocks are:
           |
           |```
           |$combined
           |```""".stripMargin
      )
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
          s"""$instructions
             |
             |$statusHint.
             |
             |The blocks are in `$outputFile`
             |(the file may be large — read it in parts if needed).""".stripMargin
        )
      finally
        val _ = os.remove(outputFile)

// Public (not `private[review]`): as the case class's companion it carries the
// synthesized `apply`/`unapply`, so restricting it would make `Lint(...)`
// inaccessible outside the package despite the class being public.
// `InlineLintThreshold` stays package-private on its own member below.
object Lint:
  /** Max combined lint-output length (chars) inlined into the summariser
    * prompt; larger output spills to a file (see [[lint]]). Sized so a typical
    * lint failure inlines — keeping the gate working under sandboxed agents
    * that can't read outside their worktree — while a full build/test dump goes
    * to a file rather than flooding the context.
    */
  private[review] val InlineLintThreshold: Int = 8 * 1024
