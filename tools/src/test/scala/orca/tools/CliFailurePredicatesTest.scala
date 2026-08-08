package orca.tools

/** Pins the recoverable-failure stderr/stdout predicates against realistic git
  * and gh output. These match human-readable CLI text (the tools expose no
  * machine-readable signal for these cases), so the samples here double as
  * documentation of what output each predicate is expected to classify.
  */
class CliFailurePredicatesTest extends munit.FunSuite:

  test("isNonFastForward matches a non-fast-forward rejection"):
    val stderr =
      """To github.com:owner/repo.git
        | ! [rejected]        feat -> feat (non-fast-forward)
        |error: failed to push some refs to 'github.com:owner/repo.git'""".stripMargin
    assert(OsGitTool.isNonFastForward(stderr))

  test("isNonFastForward matches a fetch-first rejection"):
    val stderr =
      """To github.com:owner/repo.git
        | ! [rejected]        feat -> feat (fetch first)
        |error: failed to push some refs to 'github.com:owner/repo.git'""".stripMargin
    assert(OsGitTool.isNonFastForward(stderr))

  test(
    "isRemoteDeclined matches a hook rejection (GH006) — not non-fast-forward"
  ):
    val stderr = "remote: error: GH006: Protected branch update failed"
    assert(OsGitTool.isRemoteDeclined(stderr))
    assert(!OsGitTool.isNonFastForward(stderr))

  test("isRemoteDeclined matches a protected-branch decline"):
    assert(
      OsGitTool.isRemoteDeclined(
        "remote: error: GH013: protected branch hook declined"
      )
    )

  test("isNonFastForward and isRemoteDeclined do not match an auth failure"):
    val stderr =
      "fatal: Authentication failed for 'https://github.com/owner/repo.git/'"
    assert(!OsGitTool.isNonFastForward(stderr))
    assert(!OsGitTool.isRemoteDeclined(stderr))

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

  test(
    "isBranchNotPushed matches the validation error for a missing --head branch"
  ):
    // Verbatim GitHub API response (HTTP 422) when `--head` names a branch
    // that was never pushed.
    val combined =
      "pull request create failed: GraphQL: Head sha can't be blank, " +
        "Base sha can't be blank, No commits between main and feat, " +
        "Head ref must be a branch (createPullRequest)"
    assert(OsGitHubTool.isBranchNotPushed(combined))
    assert(!OsGitHubTool.isNoCommitsToPr(combined))

  test("the gh predicates do not match an unrelated failure"):
    val combined = "error: could not resolve to a repository"
    assert(!OsGitHubTool.isPrAlreadyExists(combined))
    assert(!OsGitHubTool.isNoCommitsToPr(combined))
    assert(!OsGitHubTool.isBranchNotPushed(combined))
