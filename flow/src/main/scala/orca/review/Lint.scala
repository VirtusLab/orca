package orca.review

// Compiled under capture checking (the two language imports below) to match
// `ReviewLoop.scala`, where `lint` is fanned out alongside the reviewers
// through the CheckedPar funnel (ADR 0018 §6).
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{FlowContext, InStage, OrcaDir}
import orca.agents.Agent

/** The lint gate `reviewAndFixLoop` runs alongside the reviewers each round:
  * `command` (run via `bash -c`, e.g. `"cargo check --tests"`) and the `agent`
  * that summarises its output into a `ReviewResult` (a cheap model —
  * `claude.haiku`, `codex.mini` — since the summary is a small fold). Bundling
  * the pair into one value (rather than `reviewAndFixLoop` taking a separate
  * `lintCommand: Option[String]` / `lintAgent: Option[Agent[?]]`) makes "a
  * command with no summariser" unrepresentable, so the loop no longer needs a
  * runtime `require` to reject that half-set state.
  */
case class Lint(command: String, agent: Agent[?])

/** Run `command` via `bash -c`, capture both stdout and stderr, and ask `agent`
  * to summarise it as a `ReviewResult`. An empty output short-circuits to
  * `ReviewResult.empty` so clean runs skip the round-trip to the LLM. Override
  * `instructions` when the lint produces unusual shapes the default phrasing
  * doesn't fit.
  *
  * How the output reaches the agent depends on its size:
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
  *     lives under `<workDir>/.orca/cache/` — NOT `/tmp` — so a sandboxed agent
  *     whose worktree is in-sandbox can still reach it. It's removed in the
  *     `finally` before this call returns.
  *
  * The command's exit status is passed alongside either way: a zero status
  * usually means nothing to report, so the agent can return empty without
  * reading further.
  *
  * The LLM is invoked read-only: the task is text-in / JSON-out, and the agent
  * may verify a lint claim against the sources it references but should never
  * edit during the summarisation step.
  */
def lint(
    command: String,
    agent: Agent[?],
    instructions: String = ReviewLoopPrompts.SummariseLint
)(using ctx: FlowContext, ev: InStage): ReviewResult =
  val proc = os
    .proc("bash", "-c", command)
    .call(check = false, mergeErrIntoOut = true)
  val output = proc.out.text().trim
  if output.isEmpty then ReviewResult.empty
  else
    def summarise(prompt: String): ReviewResult =
      agent.withReadOnly
        .resultAs[ReviewResult]
        .autonomous
        .run(prompt, emitPrompt = false)
    val statusHint =
      s"`$command` exited with status ${proc.exitCode}. A zero status usually " +
        "means it succeeded with nothing to report — return an empty result"
    if output.length <= Lint.InlineLintThreshold then
      summarise(
        s"""$instructions
           |
           |$statusHint in that case.
           |
           |Otherwise, its entire combined stdout+stderr is:
           |
           |```
           |$output
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
      val orcaDir = OrcaDir.ensureCache(ctx.workDir)
      val outputFile =
        os.temp(
          output,
          dir = orcaDir,
          prefix = "lint-",
          suffix = ".txt",
          deleteOnExit = false
        )
      try
        summarise(
          s"""$instructions
             |
             |$statusHint without reading the file in that case.
             |
             |Otherwise its entire combined stdout+stderr is in `$outputFile`
             |(it may be large — read it in parts if needed).""".stripMargin
        )
      finally
        val _ = os.remove(outputFile)

// Public (not `private[review]`): it's the case class's companion, and the
// case class is exported for its `apply` — a `private[review]` object here
// would carry the synthesized `apply`/`unapply` down with it, making
// `Lint(command, agent)` inaccessible from outside the package despite the
// class itself being public. `InlineLintThreshold` stays package-private on
// its own member, below.
object Lint:
  /** Max lint-output length (in chars) inlined straight into the summariser
    * prompt; larger output is spilled to a file instead (see [[lint]]). Sized
    * so a typical lint/`cargo check` failure (a handful of diagnostics) inlines
    * — which is what keeps the lint gate working under sandboxed autonomous
    * agents that can't read files outside their worktree — while a full
    * build/test dump still goes to a file rather than flooding the context.
    */
  private[review] val InlineLintThreshold: Int = 8 * 1024
