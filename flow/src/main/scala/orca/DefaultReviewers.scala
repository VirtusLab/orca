package orca

/** Canonical system prompts for the reviewers the library ships with. Exposed
  * so callers can tune or extend the set without rewriting the defaults. The
  * set mirrors Claude Code's built-in review agents (performance, readability,
  * test, code-functionality, abstraction) so flow scripts that lean on the
  * defaults get the same coverage regardless of backend.
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

  val CodeFunctionality: String =
    """You are a functionality reviewer. Check whether the code changes
      |correctly implement the stated task by reading the happy path and
      |the obvious edge cases. Flag off-by-one errors, inverted
      |comparisons, missing error handling that the contract requires,
      |and logic that doesn't match the task description. Rate confidence
      |high when the bug breaks documented behavior; lower when it's a
      |hypothetical edge case.
      |""".stripMargin

  val Abstraction: String =
    """You are an abstraction reviewer. Look for copy-pasted blocks,
      |repeated control-flow shapes across files, hand-rolled
      |implementations of operations the standard library or framework
      |already provides, and ad-hoc parsing where a typed helper exists.
      |Flag opportunities to reuse existing helpers. Be conservative —
      |rate confidence high only when duplication is verbatim or
      |near-verbatim, lower when the refactor is stylistic.
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
  def startSession(
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], String) = delegate.startSession(prompt, config)
  def continueSession(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): String = delegate.continueSession(sessionId, prompt, config)
  def withConfig(config: LlmConfig): LlmTool[B] =
    new NamedLlmTool(name, delegate.withConfig(config))
  def withSystemPrompt(prompt: String): LlmTool[B] =
    new NamedLlmTool(name, delegate.withSystemPrompt(prompt))
  def resultAs[O: JsonData: Announce]: LlmCall[B, O] = delegate.resultAs[O]

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
  ),
  new NamedLlmTool(
    "code-functionality",
    base.withSystemPrompt(ReviewerPrompts.CodeFunctionality)
  ),
  new NamedLlmTool(
    "abstraction",
    base.withSystemPrompt(ReviewerPrompts.Abstraction)
  )
)
