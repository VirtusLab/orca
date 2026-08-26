package orca.review

import orca.plan.{Task, Title}
import orca.util.TextUtil

class ReviewLoopPromptsTest extends munit.FunSuite:

  // The template hard-wraps, so compare against whitespace-collapsed text:
  // the assertions are about the wording reaching the reviewer, not the
  // line breaks it arrives with.
  private def rendered(
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
        base = base,
        declined = Nil
      )
    )

  test("initialReview asks for findings worth fixing, not hedges"):
    // The reviewer's own judgement is the only filter between a finding and the
    // fixer, so the prompt has to say what clears it.
    val prompt = rendered()
    assert(
      prompt.contains(
        "Report a finding only if you believe it should be fixed."
      ),
      prompt
    )
    assert(
      prompt.contains(
        "Do not report a hedge, a hunch you did not verify, or a style opinion."
      ),
      prompt
    )
    assert(
      prompt.contains(
        "If you verified it, report it — whether the fix is a one-line change " +
          "or a rewrite."
      ),
      prompt
    )

  test("initialReview labels the user's request and the planner's description"):
    // The labels are the point: a reviewer that can't tell the two apart can't
    // aim a finding at the planned choice rather than the code.
    val prompt = rendered()
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
      task = Task(Title("add a median function"), "add a median function"),
      userRequest = "add a median function"
    )
    assert(!prompt.contains("The user's request for this run:"), prompt)
    assert(!prompt.contains("The planner's description of this task:"), prompt)

  test("initialReview tells reviewers the plan is not evidence"):
    val prompt = rendered()
    assert(
      prompt.contains(
        "The task above says what was decided, not that the decision is " +
          "correct."
      ),
      prompt
    )

  test("initialReview names the commit the diff was sampled against"):
    // Sent alongside the diff, not instead of it: a reviewer can read the repo
    // at that commit, via the MCP tool or a shell.
    val prompt = rendered(base = Some("abc1234"))
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
