package orca.runner

import orca.{RunTarget, Uncommitted}
import orca.tools.PrHandle

/** Tests for the rule that decides where a successful run leaves HEAD. The
  * `CurrentBranch`/`Worktree` arm never reads `openedPr`, so those targets are
  * stated once, with a PR — the case that would be `ReturnToStart` if the arm
  * did read it.
  */
class BranchHandoffTest extends munit.FunSuite:

  private val pr = Some(PrHandle("github.com", "acme", "widgets", 1))
  private val newBranch = RunTarget.NewBranch(Uncommitted.Stash)
  private val currentBranch = RunTarget.CurrentBranch(Uncommitted.Stash)

  test("a new-branch run that opened a PR returns to its start branch"):
    assertEquals(
      BranchHandoff.of(newBranch, None, pr),
      BranchHandoff.ReturnToStart
    )

  test("a new-branch run that opened no PR stays put"):
    assertEquals(BranchHandoff.of(newBranch, None, None), BranchHandoff.StayPut)

  test("a new-branch run inside a worktree stays put even with a PR"):
    // The checkout is orca's own worktree; a shell resume relaunched without
    // --worktree still reports NewBranch, which is why the worktree decides.
    assertEquals(
      BranchHandoff.of(newBranch, Some(os.pwd), pr),
      BranchHandoff.StayPut
    )

  test("a skip-branch run stays put even with a PR"):
    assertEquals(
      BranchHandoff.of(currentBranch, None, pr),
      BranchHandoff.StayPut
    )

  test("a worktree run stays put even with a PR"):
    assertEquals(
      BranchHandoff.of(RunTarget.Worktree, Some(os.pwd), pr),
      BranchHandoff.StayPut
    )
