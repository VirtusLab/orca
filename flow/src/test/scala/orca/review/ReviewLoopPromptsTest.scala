package orca.review

import orca.agents.given
import orca.plan.{Task, Title}
import orca.util.{JsonSchemaGen, TextUtil}

class ReviewLoopPromptsTest extends munit.FunSuite:

  // The template hard-wraps, so compare against whitespace-collapsed text:
  // the assertions are about the wording reaching the reviewer, not the
  // line breaks it arrives with.
  private def rendered(
      gate: ConfidenceGate,
      base: Option[String] = None,
      task: Task = Task(Title("do the thing"), "split the list in halves"),
      userRequest: String = "add a median function"
  ): String =
    TextUtil.collapseWhitespace(
      ReviewLoopPrompts.initialReview(
        task = task,
        userRequest = userRequest,
        diff = "",
        diffIntro = "Diff:",
        gate = gate,
        base = base,
        declined = Nil
      )
    )

  test("initialReview renders the caller's bars"):
    // The bars are a parameter, so a caller-tuned gate must reach the prompt —
    // otherwise reviewers calibrate against a threshold that isn't applied.
    val prompt =
      rendered(
        ConfidenceGate(
          critical = Confidence.orThrow(0.1),
          warning = Confidence.orThrow(0.2),
          info = Confidence.orThrow(0.3)
        )
      )
    assert(prompt.contains("Critical 0.1, Warning 0.2, Info 0.3"), prompt)

  test("initialReview labels the user's request and the planner's description"):
    // The labels are the point: a reviewer that can't tell the two apart can't
    // aim a finding at the planned choice rather than the code.
    val prompt = rendered(ConfidenceGate.default)
    assert(
      prompt.contains(
        "The user's request for this run: add a median function"
      ),
      prompt
    )
    assert(
      prompt.contains(
        "The planner's description of this task: split the list in halves"
      ),
      prompt
    )

  test("initialReview drops a context section repeating the task title"):
    // Saying the same words three times is pure token cost.
    val prompt = rendered(
      ConfidenceGate.default,
      task = Task(Title("add a median function"), "add a median function"),
      userRequest = "add a median function"
    )
    assert(!prompt.contains("The user's request for this run:"), prompt)
    assert(!prompt.contains("The planner's description of this task:"), prompt)

  test("initialReview tells reviewers the plan is not evidence"):
    val prompt = rendered(ConfidenceGate.default)
    assert(
      prompt.contains(
        "The task above says what was decided, not that the decision is " +
          "correct."
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
        ReReviewChanges.AlreadySeen(LastSent.NoteOnly("")),
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

  test("reReview says the task is unchanged rather than repeating it"):
    // A resumed reviewer is the same conversation that got the initial prompt,
    // so the request and the description are already there — re-sending them
    // every round would cost tokens for nothing.
    val prompt = TextUtil.collapseWhitespace(
      ReviewLoopPrompts.reReview(
        ReReviewChanges.AlreadySeen(LastSent.NoteOnly("")),
        Nil
      )
    )
    assert(
      prompt.contains(
        "Everything the initial prompt said still applies: the task it " +
          "described is the same"
      ),
      prompt
    )

  test("reReview says nothing about declines when the fixer declined nothing"):
    // Same separator argument as the base-commit section above.
    val prompt = TextUtil.collapseWhitespace(
      ReviewLoopPrompts.reReview(
        ReReviewChanges.AlreadySeen(LastSent.NoteOnly("")),
        Nil
      )
    )
    assert(!prompt.contains("The fixer declined"), prompt)
