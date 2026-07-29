package orca.review

import orca.agents.given
import orca.util.{JsonSchemaGen, TextUtil}

class ReviewLoopPromptsTest extends munit.FunSuite:

  // The template hard-wraps, so compare against whitespace-collapsed text:
  // the assertions are about the wording reaching the reviewer, not the
  // line breaks it arrives with.
  private def rendered(gate: ConfidenceGate): String =
    TextUtil.collapseWhitespace(
      ReviewLoopPrompts.initialReview("do the thing", "", gate)
    )

  test("initialReview renders the caller's bars"):
    // The bars are a parameter, so a caller-tuned gate must reach the prompt —
    // otherwise reviewers calibrate against a threshold that isn't applied.
    val prompt =
      rendered(ConfidenceGate(critical = 0.1, warning = 0.2, info = 0.3))
    assert(prompt.contains("Critical 0.1, Warning 0.2, Info 0.3"), prompt)

  test("initialReview tells reviewers the plan is not evidence"):
    val prompt = rendered(ConfidenceGate.default)
    assert(
      prompt.contains(
        "The task description, and the plan behind it, are context — not " +
          "evidence that a decision is correct."
      ),
      prompt
    )

  test("the prompt and the generated schema state the same contract"):
    // The confidence contract is written twice — at length in
    // initial-review.md, and as the `@description` on ReviewIssue.confidence
    // that reaches backends through the JSON schema. Nothing shares the string
    // (a tapir annotation takes a literal), so this pins the clause that makes
    // the contract distinctive: drop it from either copy and this fails.
    val clause = "deference to the task's plan"
    val prompt = rendered(ConfidenceGate.default)
    val schema = JsonSchemaGen[ReviewResult]
    assert(prompt.contains(clause), prompt)
    assert(schema.contains(clause), schema)
