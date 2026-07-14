package orca.review

// Compiled under capture checking (the two language imports below) to match
// `ReviewLoop.scala`, where `lint` is fanned out alongside the reviewers
// through the CheckedPar funnel (ADR 0018 §6).
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{FlowContext, InStage, OrcaDir}
import orca.agents.Agent

/** The lint gate `reviewAndFixLoop` runs alongside the reviewers each round:
  * `commands` (each run via `bash -c`, in file/declaration order — e.g. one per
  * stack half of a multi-stack repo, per ADR 0019's `lint` list) and the
  * `agent` that summarises their labeled, concatenated output into a
  * `ReviewResult` (a cheap model — `claude.haiku`, `codex.mini` — since the
  * summary is a small fold). Bundling the pair into one value (rather than
  * `reviewAndFixLoop` taking a separate command-list and agent parameter) makes
  * "commands with no summariser" unrepresentable, so the loop no longer needs a
  * runtime `require` to reject that half-set state.
  */
case class Lint(commands: List[String], agent: Agent[?])

/** One executed lint command, rendered for the summariser as a labeled block:
  * `$ <command> (exit <status>)`, then the trimmed output on the following
  * lines. An empty output keeps the label line alone, so the summariser still
  * sees "ran, produced nothing, exited N".
  */
private case class LintRun(command: String, exitCode: Int, output: String):
  def labelled: String =
    val label = s"$$ $command   (exit $exitCode)"
    if output.isEmpty then label else s"$label\n$output"

/** Run each of `commands` via `bash -c`, in order, in the flow's working tree
  * (`ctx.workDir`), capturing each command's combined stdout+stderr; then ask
  * `agent` — in a single pass — to summarise the concatenation as a
  * `ReviewResult`. Every command runs even when an earlier one fails, so a
  * broken first linter doesn't hide the second's diagnostics from the same
  * review round; each block carries its own command and exit status. When every
  * command exits 0 with empty output — including `commands = Nil`, a disabled
  * gate — the call short-circuits to `ReviewResult.empty` and skips the LLM
  * round-trip entirely (a silent nonzero exit still reaches the summariser: a
  * linter can fail with no stdout). Override `instructions` when the lint
  * produces unusual shapes the default phrasing doesn't fit.
  *
  * How the combined text reaches the agent depends on its size:
  *
  *   - **Small (≤ [[Lint.InlineLintThreshold]] chars):** inlined directly into
  *     the prompt. This is the common case (a `cargo check` with a handful of
  *     errors), and inlining is what makes lint work under a sandboxed
  *     autonomous agent — opencode's autonomous mode denies file reads outside
  *     its worktree, so a `/tmp` file reference was silently unreadable and the
  *     lint gate became a no-op ("lint: 0 issues" every round).
  *   - **Large (> threshold):** spilled to a file the agent reads with its
  *     read-only tools (in chunks if needed), because an unbounded build/test
  *     run (hundreds of KB) would overflow the model's context window. The file
  *     holds the same labeled blocks the inline path embeds, and lives under
  *     `<workDir>/.orca/cache/` — NOT `/tmp` — so a sandboxed agent whose
  *     worktree is in-sandbox can still reach it. It's removed in the `finally`
  *     before this call returns.
  *
  * The LLM is invoked read-only: the task is text-in / JSON-out, and the agent
  * may verify a lint claim against the sources it references but should never
  * edit during the summarisation step.
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
  // Vacuously true for `commands = Nil`, so an empty list no-ops. The
  // exit-status guard matters: a clean run must be silent AND successful
  // everywhere before the summariser is skipped.
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
      // Too large to inline without risking the model's context window, so
      // spill it to a file the agent reads with its read-only tools. The file
      // lives under the flow's working tree (NOT `/tmp`) so sandboxed
      // autonomous agents — e.g. opencode, which denies reads outside its
      // worktree — can still reach it.
      //
      // Commit-safety: `.orca/cache/` self-ignores via the `.gitignore` that
      // `ensureCache` writes before anything else can land in the dir, so a
      // stage's `git add -A` can never sweep the spill file — even after a
      // crash mid-lint. The `finally` still removes the file before this call
      // returns. `deleteOnExit = false`: the `finally` owns cleanup, so we
      // skip the JVM-exit hook (one per lint call would otherwise accumulate
      // over a long run).
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

// Public (not `private[review]`): it's the case class's companion, and the
// case class is exported for its `apply` — a `private[review]` object here
// would carry the synthesized `apply`/`unapply` down with it, making
// `Lint(commands, agent)` inaccessible from outside the package despite the
// class itself being public. `InlineLintThreshold` stays package-private on
// its own member, below.
object Lint:
  /** Max combined lint-output length (in chars) inlined straight into the
    * summariser prompt; larger output is spilled to a file instead (see
    * [[lint]]). Sized so a typical lint/`cargo check` failure (a handful of
    * diagnostics) inlines — which is what keeps the lint gate working under
    * sandboxed autonomous agents that can't read files outside their worktree —
    * while a full build/test dump still goes to a file rather than flooding the
    * context.
    */
  private[review] val InlineLintThreshold: Int = 8 * 1024
