package orca.review

import orca.agents.given
import orca.plan.Title
import orca.util.{JsonSchemaGen, TextUtil}
import ox.either.orThrow

class ReviewLoopPromptsTest extends munit.FunSuite:

  // The template hard-wraps, so compare against whitespace-collapsed text:
  // the assertions are about the wording reaching the reviewer, not the
  // line breaks it arrives with.
  private def rendered(
      gate: ConfidenceGate,
      base: Option[String] = None
  ): String =
    TextUtil.collapseWhitespace(
      ReviewLoopPrompts.initialReview("do the thing", "", gate, base, Nil)
    )

  test("initialReview renders the caller's bars"):
    // The bars are a parameter, so a caller-tuned gate must reach the prompt —
    // otherwise reviewers calibrate against a threshold that isn't applied.
    val prompt =
      rendered(
        ConfidenceGate(
          critical = Confidence(0.1).orThrow,
          warning = Confidence(0.2).orThrow,
          info = Confidence(0.3).orThrow
        )
      )
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

  test("initialReview names the commit the diff was sampled against"):
    // Sent alongside the diff, not instead of it: a reviewer can read the repo
    // at that commit, via the MCP tool or a shell.
    val prompt = rendered(ConfidenceGate.default, base = Some("abc1234"))
    assert(
      prompt.contains("everything that changed since commit abc1234"),
      prompt
    )
    assert(prompt.contains("`git_file_at` at that commit"), prompt)
    assert(prompt.contains("git show abc1234:<path>"), prompt)

  test("reReview carries the fixer's declines as a position, not a ruling"):
    val prompt = TextUtil.collapseWhitespace(
      ReviewLoopPrompts.reReview(
        ReReviewChanges.AlreadySeen,
        List(IgnoredIssue(Title("rename the field"), "the name is on our API"))
      )
    )
    assert(
      prompt.contains("- rename the field: the name is on our API"),
      prompt
    )
    assert(
      prompt.contains(
        "That is the fixer's position, not a ruling. If you still think a " +
          "finding is real, report it again and say why the reason is wrong."
      ),
      prompt
    )

  test("reReview says nothing about declines when the fixer declined nothing"):
    // Same separator argument as the base-commit section above.
    val prompt = TextUtil.collapseWhitespace(
      ReviewLoopPrompts.reReview(ReReviewChanges.AlreadySeen, Nil)
    )
    assert(!prompt.contains("The fixer declined"), prompt)
