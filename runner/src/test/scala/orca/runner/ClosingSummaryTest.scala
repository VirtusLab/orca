package orca.runner

import orca.progress.CommitHash

/** The worktree shapes of [[ClosingSummary.lines]]. The non-worktree ones are
  * pinned end-to-end by `FlowLifecycleTest`'s closing-summary cases, which run
  * through `teardownSuccess`.
  */
class ClosingSummaryTest extends munit.FunSuite:

  private val base: CommitHash =
    CommitHash.from("0123456789abcdef0123456789abcdef01234567").get

  // Never touched: `lines` only formats it into two strings.
  private val worktree: os.Path =
    os.root / "repo" / ".orca" / "worktrees" / "ab12cd34"

  test("a worktree run names where the work is and scopes the diff to it"):
    assertEquals(
      ClosingSummary
        .lines("work", Some(RunChanges(base, 2, "work")), Some(worktree)),
      List(
        s"done — the work is in $worktree on branch 'work'",
        s"2 file(s) changed since ${base.short}",
        s"""next: git -C "$worktree" diff ${base.short}"""
      )
    )

  test("a worktree run that changed nothing offers no diff"):
    assertEquals(
      ClosingSummary
        .lines("work", Some(RunChanges(base, 0, "work")), Some(worktree)),
      List(
        s"done — the work is in $worktree on branch 'work'",
        "no files changed"
      )
    )

  test("HEAD leaving the counted branch still names the branch with the work"):
    // What a `returnToStartBranch` flow leaves: HEAD back on the branch the
    // worktree was created on, which holds none of the run's commits.
    assertEquals(
      ClosingSummary.lines(
        "orca-worktree-ab12cd34",
        Some(RunChanges(base, 2, "work")),
        Some(worktree)
      ),
      List(
        s"done — the work is in $worktree on branch 'work'",
        s"2 file(s) changed since ${base.short}",
        s"""next: git -C "$worktree" diff ${base.short}..work"""
      )
    )

  test("a path with a space stays one argument in the offered command"):
    val spaced = os.root / "home" / "me" / "my repo" / ".orca" / "wt"
    assertEquals(
      ClosingSummary
        .lines("work", Some(RunChanges(base, 1, "work")), Some(spaced))
        .last,
      s"""next: git -C "$spaced" diff ${base.short}"""
    )
