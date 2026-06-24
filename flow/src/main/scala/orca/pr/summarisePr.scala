package orca.pr

import orca.{FlowContext, InStage}
import orca.llm.{Announce, JsonData, LlmTool}

/** What [[summarisePr]] produces: a one-line PR title and a multi-paragraph
  * body. `gh.createPr(title = …, body = …)` accepts the two fields directly, so
  * call sites typically destructure or pass them positionally.
  */
case class PrSummary(title: String, body: String) derives JsonData

object PrSummary:
  /** Silent — the calling stage already names the PR; an `Announce` summary
    * here would just repeat the title.
    */
  given Announce[PrSummary] = Announce.from(_ => "")

/** Ask `llm` to fold `diff` (and optionally `context`) into a [[PrSummary]] —
  * the one-line title and multi-paragraph body that `gh.createPr` consumes.
  *
  * `context` is rendered above the diff as a preamble; typical contents are the
  * originating issue link and title, the user prompt that drove the work, or
  * whatever the flow author wants the model to anchor the description to. Omit
  * for diff-only summarisation.
  *
  * Use a cheap model — summarisation is a small fold and doesn't benefit from a
  * frontier model. The autonomous call runs `emitPrompt = false` because the
  * diff dominates the prompt and would dwarf the event log.
  */
def summarisePr(
    llm: LlmTool[?],
    diff: String,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using FlowContext, InStage): PrSummary =
  val contextBlock = context.fold("")(c => s"$c\n\n")
  val prompt =
    s"""$instructions
       |
       |${contextBlock}Branch diff (vs base):
       |
       |```diff
       |$diff
       |```""".stripMargin
  llm.resultAs[PrSummary].autonomous.run(prompt, emitPrompt = false)._2
