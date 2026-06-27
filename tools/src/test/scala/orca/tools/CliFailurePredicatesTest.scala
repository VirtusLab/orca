package orca.tools

/** Pins the recoverable-failure stderr/stdout predicates against realistic git
  * and gh output. These match human-readable CLI text (the tools expose no
  * machine-readable signal for these cases), so the samples here double as
  * documentation of what output each predicate is expected to classify.
  */
class CliFailurePredicatesTest extends munit.FunSuite:

  test("isPushRejection matches a non-fast-forward rejection"):
    val stderr =
      """To github.com:owner/repo.git
        | ! [rejected]        feat -> feat (non-fast-forward)
        |error: failed to push some refs to 'github.com:owner/repo.git'""".stripMargin
    assert(OsGitTool.isPushRejection(stderr))

  test("isPushRejection matches a hook rejection"):
    assert(OsGitTool.isPushRejection("remote: error: GH006: rejected"))

  test("isPushRejection does not match an auth failure"):
    val stderr =
      "fatal: Authentication failed for 'https://github.com/owner/repo.git/'"
    assert(!OsGitTool.isPushRejection(stderr))

  test("isWorktreeAlreadyPresent matches an existing path"):
    assert(
      OsGitTool.isWorktreeAlreadyPresent(
        "fatal: '/tmp/wt' already exists"
      )
    )

  test("isWorktreeAlreadyPresent matches an already-checked-out branch"):
    assert(
      OsGitTool.isWorktreeAlreadyPresent(
        "fatal: 'feat' is already checked out at '/tmp/other'"
      )
    )

  test("isWorktreeAlreadyPresent does not match a generic failure"):
    assert(!OsGitTool.isWorktreeAlreadyPresent("fatal: not a git repository"))

  test("isPrAlreadyExists matches gh's duplicate-PR message (case-folded)"):
    // Verbatim gh output, mixed case — the predicate case-folds internally.
    val combined =
      "a pull request for branch \"feat\" into branch \"main\" already exists:\n" +
        "https://github.com/owner/repo/pull/7"
    assert(OsGitHubTool.isPrAlreadyExists(combined))
    assert(OsGitHubTool.isPrAlreadyExists("PR Already Exists"))

  test("isNoCommitsToPr matches the no-commits message"):
    assert(
      OsGitHubTool.isNoCommitsToPr(
        "pull request create failed: No commits between main and feat"
      )
    )

  test("isNoCommitsToPr matches the must-first-push message"):
    assert(
      OsGitHubTool.isNoCommitsToPr(
        "Must first push the current branch to a remote"
      )
    )

  test("the gh predicates do not match an unrelated failure"):
    val combined = "error: could not resolve to a repository"
    assert(!OsGitHubTool.isPrAlreadyExists(combined))
    assert(!OsGitHubTool.isNoCommitsToPr(combined))
