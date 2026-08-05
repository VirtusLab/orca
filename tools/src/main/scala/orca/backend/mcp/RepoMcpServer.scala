package orca.backend.mcp

import chimp.*
import io.circe.Codec
import orca.tools.{GitReadFailed, GitTool, ShowDetail}
import ox.Ox
import sttp.tapir.Schema
import sttp.tapir.server.netty.sync.NettySyncServer

import scala.concurrent.duration.{DurationInt, FiniteDuration}

private[mcp] case class GitShowInput(
    rev: String,
    paths: List[String] = Nil,
    stat: Boolean = false
) derives Codec,
      Schema

private[mcp] case class GitFileAtInput(rev: String, path: String)
    derives Codec,
      Schema

/** MCP server giving read-only turns the git reads they lose with the shell.
  * Two tools, both structured — the agent names a revision and paths, never a
  * command string, and orca builds the argv.
  *
  * Deliberately not `git_diff` / `git_status`, which were 82% of measured
  * reviewer git use: both re-derive the change set the prompt already carries
  * (`docs/research/run-cost/12-reviewer-tool-surface.md` §6).
  *
  * Bound on `127.0.0.1` at an ephemeral port; lifecycle is caller-owned, as for
  * [[AskUserMcpServer]].
  */
private[orca] class RepoMcpServer private[mcp] (
    val port: Int,
    stopFn: () => Unit
) extends AutoCloseable:
  val url: String = s"http://127.0.0.1:$port/mcp"

  override def close(): Unit = stopFn()

private[orca] object RepoMcpServer:

  /** MCP server name advertised to the backend. Distinct from
    * [[AskUserMcpServer.ServerName]] because the two serve different turn kinds
    * — `ask_user` blocks on a human and is interactive-only, these are fast
    * reads on autonomous read-only turns — so they are bound and torn down
    * independently.
    */
  private[orca] val ServerName: String = "orca_repo"

  private[orca] val ShowSlug: String = "git_show"
  private[orca] val FileAtSlug: String = "git_file_at"

  /** Per-call timeout. These reads are local `git` invocations; the generous
    * bound is for a `git show` on a large commit, not for a slow answer.
    */
  private[orca] val ToolTimeout: FiniteDuration = 2.minutes

  /** Chars of git output any one tool call returns. Past this the tail is
    * dropped and the result says so, so a `git show` of a huge commit costs a
    * bounded number of tokens rather than the turn's whole context.
    */
  private[mcp] val MaxOutputChars: Int = 60000

  /** Every tool slug this server exposes, for callers that must pre-approve
    * them by name.
    */
  private[orca] val ToolSlugs: Seq[String] = Seq(ShowSlug, FileAtSlug)

  /** Mount both tools on a fresh Netty binding in the enclosing Ox scope,
    * reading through `git`. The caller calls `close()` (or relies on scope
    * tear-down).
    */
  def start(git: GitTool)(using Ox): RepoMcpServer =
    val showTool = tool(ShowSlug)
      .description(
        "Show a commit: its message and diff, or with stat=true just which " +
          "files it changed. Optionally narrow the diff to specific " +
          "repository-relative paths."
      )
      .input[GitShowInput]
      .handle: in =>
        val detail = if in.stat then ShowDetail.StatOnly else ShowDetail.Full
        render(git.show(in.rev, in.paths, detail))
    val fileAtTool = tool(FileAtSlug)
      .description(
        "Read one file's full contents as of a commit, e.g. to see what it " +
          "looked like before the change under review."
      )
      .input[GitFileAtInput]
      .handle(in => render(git.fileAt(in.rev, in.path)))
    val binding = NettySyncServer()
      .port(0)
      .modifyConfig(
        _.requestTimeout(ToolTimeout).idleTimeout(ToolTimeout + 1.minute)
      )
      .addEndpoint(mcpEndpoint(List(showTool, fileAtTool), List("mcp")))
      .start()
    new RepoMcpServer(binding.port, () => binding.stop())

  /** Map a read outcome onto MCP's success/error channels, bounding the success
    * payload.
    */
  private type ToolResult = Either[String, String]

  private def render(result: Either[GitReadFailed, String]): ToolResult =
    result.left.map(_.getMessage).map(bounded)

  private def bounded(output: String): String =
    if output.length <= MaxOutputChars then output
    else
      output.take(MaxOutputChars) +
        s"\n\n[cut after $MaxOutputChars characters — narrow the request with " +
        "paths, or read the file directly]"

  /** System-prompt hint naming the tools. Read-only turns have no shell, so
    * without this the agent has no reason to look for them.
    */
  val Hint: String =
    """You have no shell. Two MCP tools cover the git reads you would
      |otherwise run: `git_show` for a commit's message and diff (pass
      |`stat: true` for just the file list, `paths` to narrow it), and
      |`git_file_at` for one file's contents at a commit — use that to see
      |a file as it was before the change under review. There is no
      |`git_diff` and no `git_status`: the change set is already in your
      |prompt, so re-deriving it wastes a turn.""".stripMargin
