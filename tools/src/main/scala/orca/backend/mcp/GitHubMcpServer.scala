package orca.backend.mcp

import chimp.*
import io.circe.Codec
import orca.tools.{Comment, GitHubTool, Issue, IssueHandle}
import ox.Ox
import sttp.tapir.Schema

import scala.concurrent.duration.{DurationInt, FiniteDuration}

private[mcp] case class GitHubIssueInput(
    owner: String,
    repo: String,
    number: Int
) derives Codec,
      Schema

/** The GitHub read a planner needs: an issue or PR's body and conversation.
  * Served host-side through [[GitHubTool]], so the agent never gets `gh` and
  * the tier stays no-edit.
  *
  * One tool, not two. GitHub numbers issues and pull requests from a single
  * sequence and serves both from the issues endpoint, so a `github_pr` would be
  * the same call under a different name.
  */
private[orca] object GitHubMcpServer:

  private[orca] val ServerName: String = "orca_github"

  private[orca] val IssueSlug: String = "github_issue"

  private[orca] val ToolSlugs: Seq[String] = Seq(IssueSlug)

  /** Per-call timeout. Covers `gh`'s own bounded retry of a transient GitHub
    * failure, so a blip costs a slow answer rather than a tool error.
    */
  private[orca] val ToolTimeout: FiniteDuration = 2.minutes

  def start(github: GitHubTool)(using Ox): McpHost =
    val issueTool = tool(IssueSlug)
      .description(
        "Read a GitHub issue or pull request: its title, state, author, body " +
          "and conversation comments. Issues and pull requests share one " +
          "number sequence, so either kind of number works."
      )
      .input[GitHubIssueInput]
      .handle(in => read(github, in))
    McpHost.start(List(issueTool), ToolTimeout)

  /** `IssueHandle.parse` does the validation: `owner` and `repo` are spliced
    * into a `gh api` request path, and its patterns are the ones that already
    * keep `?`, `#` and `..` out of one.
    */
  private def read(
      github: GitHubTool,
      in: GitHubIssueInput
  ): Either[String, String] =
    IssueHandle
      .parse(s"${in.owner}/${in.repo}#${in.number}")
      .map: handle =>
        // `GitHubTool` aborts a failed `gh` call by throwing — right for a flow
        // stage, wrong here, where an agent naming an issue that does not exist
        // should get an answer rather than end the turn. `McpHost` catches it
        // into the error channel, and bounds this result: `readIssueComments`
        // pages the whole thread, whose size is set by whoever commented.
        render(github.readIssue(handle), github.readIssueComments(handle))

  /** No `stripMargin`: an issue body is arbitrary GitHub markdown, and a table
    * row starts its line with `|`, which it would eat.
    */
  private[mcp] def render(issue: Issue, comments: List[Comment]): String =
    val header =
      s"# ${issue.title}\n\nstate: ${issue.state} · " +
        s"author: ${issue.author}\n\n${issue.body}"
    val conversation = comments.map: c =>
      s"\n\n---\n\n**${c.author}**\n\n${c.body}"
    (header +: conversation).mkString

  /** System-prompt hint. Planners have no shell and no `gh`, so without this
    * the tool is invisible.
    */
  private[orca] val Hint: String =
    """To read a GitHub issue or pull request, call the `github_issue` MCP
      |tool with its owner, repo and number — you have no shell and no `gh`.
      |It returns the body and the conversation comments, and works for
      |pull-request numbers too.""".stripMargin
