package orca.tools

import munit.FunSuite

/** Pins each [[GitHubUnavailable.explanation]] once; consumers only append
  * their next action to it.
  */
class GitHubUnavailableTest extends FunSuite:

  test("NoRemote"):
    assertEquals(
      GitHubUnavailable.NoRemote.explanation,
      "this checkout has no git remote"
    )

  test("NotGitHub offers the host login"):
    assertEquals(
      GitHubUnavailable.NotGitHub("ghe.example.com").explanation,
      "origin is on ghe.example.com, which gh holds no credential for — if " +
        "it is a GitHub Enterprise host, `gh auth login --hostname " +
        "ghe.example.com` lets orca use it"
    )

  test("NoHost names the remote and offers no login"):
    assertEquals(
      GitHubUnavailable.NoHost("/srv/repos/widgets.git").explanation,
      "origin is a local remote (/srv/repos/widgets.git), not a GitHub " +
        "repository"
    )

  test("Unreachable carries gh's reason"):
    assertEquals(
      GitHubUnavailable
        .Unreachable("github.com", "no oauth token found for github.com")
        .explanation,
      "cannot reach GitHub (github.com): no oauth token found for github.com"
    )

  test("GitUnusable carries what git threw"):
    assertEquals(
      GitHubUnavailable.GitUnusable("Cannot run program \"git\"").explanation,
      "could not run git to find the remote: Cannot run program \"git\""
    )
