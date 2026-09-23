package orca.runner

import orca.gitref.{CommitHash, Head}
import orca.progress.PublishedWork
import orca.testkit.branchName

/** The worktree shapes of [[ClosingSummary.lines]]. The non-worktree ones are
  * pinned end-to-end by `FlowLifecycleTest`'s closing-summary cases, which run
  * through `teardownSuccess`.
  */
class ClosingSummaryTest extends munit.FunSuite:

  private val base: CommitHash =
    CommitHash.from("0123456789abcdef0123456789abcdef01234567").get

  // Never touched: `lines` only formats it into two strings. The space is the
  // fixture's job — every worktree case then proves the offered command keeps
  // the path as one shell argument.
  private val worktree: os.Path =
    os.root / "my repo" / ".orca" / "worktrees" / "ab12cd34"

  test("a worktree run names where the work is and scopes the diff to it"):
    assertEquals(
      ClosingSummary
        .lines(
          Head.OnBranch(branchName("work")),
          Some(RunChanges(base, 2, branchName("work"))),
          Some(worktree),
          published = None
        ),
      List(
        s"done — the work is in $worktree on branch 'work'",
        s"2 file(s) changed since ${base.short}",
        s"""next: git -C "$worktree" diff ${base.short}"""
      )
    )

  test("a worktree run that changed nothing offers no diff"):
    assertEquals(
      ClosingSummary
        .lines(
          Head.OnBranch(branchName("work")),
          Some(RunChanges(base, 0, branchName("work"))),
          Some(worktree),
          published = None
        ),
      List(
        s"done — the work is in $worktree on branch 'work'",
        "no files changed"
      )
    )

  test("HEAD leaving the counted branch still names the branch with the work"):
    // What a run that published leaves: HEAD back on the branch the worktree
    // was created on, which holds none of the run's commits.
    assertEquals(
      ClosingSummary.lines(
        Head.OnBranch(branchName("orca-worktree-ab12cd34")),
        Some(RunChanges(base, 2, branchName("work"))),
        Some(worktree),
        published = None
      ),
      List(
        s"done — the work is in $worktree on branch 'work'",
        s"2 file(s) changed since ${base.short}",
        s"""next: git -C "$worktree" diff ${base.short}..work"""
      )
    )

  test("a published run names the reference right after where the work is"):
    assertEquals(
      ClosingSummary.lines(
        Head.OnBranch(branchName("work")),
        Some(RunChanges(base, 2, branchName("work"))),
        Some(worktree),
        published = Some(PublishedWork("https://github.com/acme/w/pull/7"))
      ),
      List(
        s"done — the work is in $worktree on branch 'work'",
        "published at https://github.com/acme/w/pull/7",
        s"2 file(s) changed since ${base.short}",
        s"""next: git -C "$worktree" diff ${base.short}"""
      )
    )
