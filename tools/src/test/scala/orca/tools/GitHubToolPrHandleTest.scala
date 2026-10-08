package orca.tools

import orca.testkit.StubGitHubTool

class GitHubToolPrHandleTest extends munit.FunSuite:
  private def on(found: GitHubAvailability): GitHubTool =
    new StubGitHubTool:
      override def availability(): GitHubAvailability = found

  private val enterprise =
    on(GitHubAvailability.Available("ghe.acme.io", "o", "r"))

  private val noRemote =
    on(GitHubAvailability.Unavailable(GitHubUnavailable.NoRemote))

  test("a short ref resolves on the checkout's host"):
    assertEquals(
      enterprise.prHandle("acme/widgets#42").map(_.url),
      Right("https://ghe.acme.io/acme/widgets/pull/42")
    )

  test("a PR URL keeps its own host and needs no probe"):
    // The stub's `availability()` throws, so any probe fails the test.
    assertEquals(
      StubGitHubTool()
        .prHandle("https://ghe.acme.io/acme/widgets/pull/7")
        .map(_.url),
      Right("https://ghe.acme.io/acme/widgets/pull/7")
    )

  test("a short ref with GitHub unavailable is a Left naming why"):
    assertEquals(
      noRemote.prHandle("acme/widgets#42"),
      Left(GitHubUnavailable.NoRemote.explanation)
    )

  test("text that is no PR reference is a Left"):
    assert(enterprise.prHandle("the uncommitted changes").isLeft)

  test("a github.com URL variant needs no probe"):
    assertEquals(
      StubGitHubTool().prHandle("github.com/acme/widgets/pull/7").map(_.url),
      Right("https://github.com/acme/widgets/pull/7")
    )

  test("a PR URL inside other text is a Left"):
    assert(
      StubGitHubTool()
        .prHandle("see https://github.com/acme/widgets/pull/7 first")
        .isLeft
    )
