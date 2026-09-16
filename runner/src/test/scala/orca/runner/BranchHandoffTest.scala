package orca.runner

import orca.progress.{BranchMode, PublishedWork}

/** Tests for the rule that decides where a successful run leaves HEAD. The
  * `Reused` arm never reads `published`, so it is stated once, as published —
  * the case that would be `ReturnToStart` if the arm did read it.
  */
class BranchHandoffTest extends munit.FunSuite:

  private val published =
    PublishedState.Published(PublishedWork("https://github.com/acme/w/pull/1"))

  test("a created-branch run that published returns to its start branch"):
    assertEquals(
      BranchHandoff.of(BranchMode.Created, None, published),
      BranchHandoff.ReturnToStart
    )

  test("a created-branch run that published nothing stays put"):
    assertEquals(
      BranchHandoff.of(BranchMode.Created, None, PublishedState.NotPublished),
      BranchHandoff.StayPut
    )

  test("a created-branch run whose log could not be read stays put"):
    // Unknown must not move the user: the handoff is the same as if the run
    // published nothing.
    assertEquals(
      BranchHandoff.of(BranchMode.Created, None, PublishedState.Unknown),
      BranchHandoff.StayPut
    )

  test("a created-branch run inside a worktree stays put even when published"):
    // The checkout is orca's own worktree, whether `--worktree` was given or a
    // shell resume relaunched into it without the flag.
    assertEquals(
      BranchHandoff.of(BranchMode.Created, Some(os.pwd), published),
      BranchHandoff.StayPut
    )

  test("a reused-branch run stays put even when published"):
    assertEquals(
      BranchHandoff.of(BranchMode.Reused, None, published),
      BranchHandoff.StayPut
    )
