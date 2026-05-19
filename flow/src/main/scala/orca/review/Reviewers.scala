package orca.review

import orca.{BackendTag, LlmTool}

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

/** Pre-configured reviewer agents built atop the supplied base tool. Each
  * reviewer has its own `name` and system prompt; callers pass them (or a
  * subset via `SelectedReviewers.pick`) to `reviewAndFixLoop`.
  */
def defaultReviewers[B <: BackendTag](base: LlmTool[B]): List[LlmTool[B]] =
  // Names are prefixed with `reviewer: ` so the per-agent token breakdown
  // groups every reviewer dimension together (matching `lint`, which the
  // review loop labels `reviewer: lint`). The non-reviewer driver agent
  // keeps its default name (`main`).
  def reviewer(name: String, prompt: String): LlmTool[B] =
    base.withSystemPrompt(prompt).withName(s"reviewer: $name")
  List(
    reviewer("performance", ReviewerPrompts.Performance),
    reviewer("readability", ReviewerPrompts.Readability),
    reviewer("test-coverage", ReviewerPrompts.TestCoverage),
    reviewer("code-functionality", ReviewerPrompts.CodeFunctionality),
    reviewer("abstraction", ReviewerPrompts.Abstraction)
  )
