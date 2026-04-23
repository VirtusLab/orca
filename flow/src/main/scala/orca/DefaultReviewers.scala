package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

/** Canonical system prompts for the reviewers the library ships with. Exposed
  * so callers can tune or extend the set without rewriting the defaults.
  */
object ReviewerPrompts:
  val Performance: String =
    """You are a performance reviewer. Analyze the code changes for CPU,
      |memory, and I/O efficiency. Flag unbounded collections, needless
      |allocations, N+1 queries, blocking calls on hot paths, and missing
      |streaming where data can be large. Rate each issue's confidence by
      |how certain you are the fix is warranted.
      |""".stripMargin

  val Readability: String =
    """You are a readability reviewer. Assess the code for clarity,
      |naming, structure, and consistency with the project's conventions.
      |Flag dense blocks that should be broken into named steps, magic
      |values, and comments that describe WHAT rather than WHY. Prefer
      |higher confidence for changes that cross module boundaries.
      |""".stripMargin

  val TestCoverage: String =
    """You are a test coverage reviewer. Look at changed production code
      |and verify each new or modified branch has a focused test covering
      |exactly one scenario. Flag untested branches, redundant tests, and
      |assertions that hide the actual contract. Rate confidence high
      |when a public API surface is untested.
      |""".stripMargin

/** LlmTool shim that overrides `name` but delegates every other call to a
  * wrapped tool. Useful for tagging a base tool with a reviewer identity so
  * `SelectedReviewers.pick` can filter the list by name.
  */
private[orca] class NamedLlmTool[B <: Backend](
    override val name: String,
    delegate: LlmTool[B]
) extends LlmTool[B]:
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String =
    delegate.ask(prompt, config)
  def withConfig(config: LlmConfig): LlmTool[B] =
    new NamedLlmTool(name, delegate.withConfig(config))
  def withSystemPrompt(prompt: String): LlmTool[B] =
    new NamedLlmTool(name, delegate.withSystemPrompt(prompt))
  def result[O: Schema: ConfiguredJsonValueCodec]: LlmCall[B, O] =
    delegate.result[O]

/** Pre-configured reviewer agents built atop the supplied base tool. Each
  * reviewer has its own `name` and system prompt; callers pass them (or a
  * subset via `SelectedReviewers.pick`) to `reviewAndFixLoop`.
  */
def defaultReviewers[B <: Backend](base: LlmTool[B]): List[LlmTool[B]] = List(
  new NamedLlmTool(
    "performance",
    base.withSystemPrompt(ReviewerPrompts.Performance)
  ),
  new NamedLlmTool(
    "readability",
    base.withSystemPrompt(ReviewerPrompts.Readability)
  ),
  new NamedLlmTool(
    "test-coverage",
    base.withSystemPrompt(ReviewerPrompts.TestCoverage)
  )
)
