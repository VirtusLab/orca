package orca.tools

import orca.PrHandle
import orca.subprocess.OsProcCliRunner

/** End-to-end tests against the real `gh` CLI. Gated on the `ORCA_INTEGRATION`
  * environment variable and require `gh auth login` on the host. The tests read
  * public data so no repo write access is needed.
  */
class OsGitHubIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  test("gh CLI is installed and invokable via the CliRunner"):
    val result = OsProcCliRunner.run(Seq("gh", "--version"))
    assertEquals(result.exitCode, 0)
    assert(
      result.stdout.toLowerCase.contains("gh version"),
      s"unexpected gh --version output: ${result.stdout}"
    )

  test("gh api user succeeds under the authenticated session"):
    val result = OsProcCliRunner.run(Seq("gh", "api", "user"))
    assertEquals(
      result.exitCode,
      0,
      s"gh api user failed; is gh auth login complete? stderr: ${result.stderr}"
    )
    assert(result.stdout.contains("\"login\""))

  test("readComments succeeds against a public issue-or-PR endpoint"):
    // GitHub's /issues/{n}/comments endpoint is shared between issues and
    // PRs; Hello-World #1 is stable public data. We assert the call returns
    // (no exception) and each entry carries a non-empty author.
    val gh = new OsGitHubTool(OsProcCliRunner)
    val handle = PrHandle("octocat", "Hello-World", 1)
    val comments = gh.readComments(handle)
    assert(comments.forall(_.author.nonEmpty))
