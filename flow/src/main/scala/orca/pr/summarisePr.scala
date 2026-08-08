package orca.pr

import orca.{BoundedDiff, FlowContext, InStage}
import orca.agents.{Announce, JsonData, Agent}

import scala.annotation.unused

/** What [[summarisePr]] produces: a one-line PR title and a multi-paragraph
  * body, consumed directly by `gh.createPr(title = …, body = …)`.
  */
case class PrSummary(title: String, body: String) derives JsonData

object PrSummary:
  /** Silent — the calling stage already names the PR. */
  given Announce[PrSummary] = Announce.from(_ => "")

/** Ask `agent` to fold `diff` (and optionally `context`) into a [[PrSummary]].
  *
  * `context` is rendered above the diff as a preamble — typically the
  * originating issue link and title, or the user prompt that drove the work.
  * Omit for diff-only summarisation.
  *
  * A `diff` too large to summarise whole is cut short
  * ([[BoundedDiff.prPayload]]) rather than sent as is, which no context window
  * would take.
  *
  * Use a cheap model. The autonomous call runs `emitPrompt = false` because the
  * diff dominates the prompt and would dwarf the event log.
  */
def summarisePr(
    agent: Agent[?],
    diff: String,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using @unused ctx: FlowContext, ev: InStage): PrSummary =
  val contextBlock = context.fold("")(c => s"$c\n\n")
  // No `stripMargin`: a diff context line is `" " + source`, so it would eat
  // the leading `|` of every margin block and markdown table in the branch.
  val prompt =
    s"$instructions\n\n${contextBlock}Branch diff (vs base):\n\n" +
      s"```diff\n${BoundedDiff.prPayload(diff)}\n```"
  agent.resultAs[PrSummary].autonomous.run(prompt, emitPrompt = false)
