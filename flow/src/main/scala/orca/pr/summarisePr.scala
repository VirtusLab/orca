package orca.pr

import orca.FlowContext
import orca.llm.LlmTool

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
)(using FlowContext): PrSummary =
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
