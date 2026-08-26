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

  private def reRendered(declined: List[IgnoredIssue] = Nil): String =
    TextUtil.collapseWhitespace(
      ReviewLoopPrompts.reReview(
        ReReviewChanges.AlreadySeen(LastSent.NoteOnly("")),
        declined
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
    assert(
      prompt.contains(
        "A deliberate or planned choice is evidence of intent, not of " +
          "correctness"
      ),
      prompt
    )

  test("the always-report list is worded once, in MandatoryCategories"):
    assertEquals(
      ReviewLoopPrompts.MandatoryCategories,
      "user data loss, silent inversion of what the user asked for, or a " +
        "blocked or hung process"
    )

  test("initialReview makes the deference-prone categories mandatory"):
    // Asserting via the constant checks the {{mandatoryCategories}}
    // placeholder was substituted; the list's wording is pinned in its own
    // test above.
    val prompt = rendered()
    assert(
      prompt.contains(
        "A finding whose consequence is " +
          ReviewLoopPrompts.MandatoryCategories +
          " must always be reported, at the severity that consequence " +
          "deserves — even where the plan explicitly chose the behaviour."
      ),
      prompt
    )
    assert(
      prompt.contains(
        "\"One-line fix\" describes cost, not severity. Never downgrade a " +
          "finding because the remedy is small."
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
    val prompt = reRendered(
      List(IgnoredIssue(Title("rename the field"), "the name is on our API"))
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
    assert(
      prompt.contains(
        "\"The plan chose this\" is not on its own a sufficient answer for a " +
          "finding in the always-report categories below — re-report such a " +
          "finding."
      ),
      prompt
    )
    // "below" holds: the template's own copy of the list renders after this
    // block.
    assert(
      prompt.indexOf("always-report categories below") <
        prompt.indexOf(ReviewLoopPrompts.MandatoryCategories),
      prompt
    )

  test("reReview says the task is unchanged rather than repeating it"):
    // A resumed reviewer is the same conversation that got the initial prompt,
    // so the request and the description are already there — re-sending them
    // every round would cost tokens for nothing.
    val prompt = reRendered()
    assert(
      prompt.contains(
        "Everything the initial prompt said still applies: the task it " +
          "described is the same"
      ),
      prompt
    )
    assert(
      prompt.contains(
        "a planned choice is still evidence of intent and not of correctness, " +
          "fix cost still never lowers severity, and " +
          ReviewLoopPrompts.MandatoryCategories +
          " are still always reported."
      ),
      prompt
    )

  test("reReview checks the option taken, not that something was done"):
    // Which alternative was taken is readable from the code, so verifying it
    // needs no fixer data — the loop never sends fixed titles to reviewers.
    val prompt = reRendered()
    assert(
      prompt.contains(
        "work out from the code which one was taken, and check that that " +
          "option resolves the original concern — not merely that something " +
          "was done."
      ),
      prompt
    )

  test("Fix asks which alternative was taken"):
    assert(
      TextUtil
        .collapseWhitespace(ReviewLoopPrompts.Fix)
        .contains(
          "Where a comment's suggestion offers alternatives (\"do X, or " +
            "document why Y is safe\"), say which one you took, after the " +
            "title"
        ),
      ReviewLoopPrompts.Fix
    )

  test("SelectReviewers asks the picker to justify what it left out"):
    // Nothing in the flow reads the rationale; requiring it is what makes the
    // picker walk each excluded reviewer's checklist before dropping it.
    val prompt = TextUtil.collapseWhitespace(ReviewLoopPrompts.SelectReviewers)
    assert(
      prompt.contains(
        "In `exclusionsRationale`, say in one short sentence per excluded " +
          "reviewer why its clauses don't match"
      ),
      prompt
    )

  test("SelectReviewers points the picker at the inclusion checklists"):
    val prompt = TextUtil.collapseWhitespace(ReviewLoopPrompts.SelectReviewers)
    assert(
      prompt.contains(
        "check its clauses against the change, include it the moment one " +
          "matches"
      ),
      prompt
    )
    // The pre-existing rule the checklists narrow but must not replace.
    assert(
      prompt.contains(
        "When you are unsure whether a reviewer applies, include it"
      ),
      prompt
    )

  test("reReview says nothing about declines when the fixer declined nothing"):
    // Same separator argument as the base-commit section above.
    val prompt = reRendered()
    assert(!prompt.contains("The fixer declined"), prompt)
