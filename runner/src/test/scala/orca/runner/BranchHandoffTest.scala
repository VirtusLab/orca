package orca.runner

import orca.progress.BranchMode
import orca.tools.PrHandle

/** Tests for the rule that decides where a successful run leaves HEAD. The
  * `Reused` arm never reads `openedPr`, so it is stated once, with a PR — the
  * case that would be `ReturnToStart` if the arm did read it.
  */
class BranchHandoffTest extends munit.FunSuite:

  private val pr = Some(PrHandle("github.com", "acme", "widgets", 1))

  test("a created-branch run that opened a PR returns to its start branch"):
    assertEquals(
      BranchHandoff.of(BranchMode.Created, None, pr),
      BranchHandoff.ReturnToStart
    )

  test("a created-branch run that opened no PR stays put"):
    assertEquals(
      BranchHandoff.of(BranchMode.Created, None, None),
      BranchHandoff.StayPut
    )

  test("a created-branch run inside a worktree stays put even with a PR"):
    // The checkout is orca's own worktree, whether `--worktree` was given or a
    // shell resume relaunched into it without the flag.
    assertEquals(
      BranchHandoff.of(BranchMode.Created, Some(os.pwd), pr),
      BranchHandoff.StayPut
    )

  test("a reused-branch run stays put even with a PR"):
    assertEquals(
      BranchHandoff.of(BranchMode.Reused, None, pr),
      BranchHandoff.StayPut
    )
