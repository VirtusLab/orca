package orca.tools.codex

import orca.backend.CliArgs
import orca.backend.mcp.AskUserMcpServer
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  EnforcementCell,
  WireSessionId,
  ToolSet,
  TurnDispatch
}

/** Maps `AgentConfig` fields to `codex exec` CLI flags. `systemPrompt` is not
  * handled here — codex doesn't accept an `--append-system-prompt` equivalent
  * on `exec`, so the backend folds it into the user prompt before this method
  * runs. `onUnapproved` and `retrySchedule` have no CLI shape and live at the
  * orchestrator layer.
  *
  * codex exec is one-shot: each call processes one prompt and exits. Multi-turn
  * happens via `codex exec resume <thread_id>`. We expose both shapes via
  * [[exec]] / [[execResume]].
  */
private[codex] object CodexArgs:

  /** Single-turn `codex exec --json [<prompt>]` invocation. */
  def exec(
      prompt: String,
      config: AgentConfig,
      outputSchemaFile: Option[os.Path],
      workDir: os.Path,
      mcpServerUrl: Option[String] = None
  ): Seq[String] =
    Seq("codex") ++
      mcpServerArgs(mcpServerUrl) ++
      networkConfigArgs(config) ++
      Seq("exec", "--json") ++
      sandboxArgs(config) ++
      CliArgs.modelArgs(config) ++
      cwdArgs(workDir) ++
      // codex bails if it can't tell whether cwd is a git repo, a poor fit
      // for tests and one-off invocations against arbitrary directories.
      Seq("--skip-git-repo-check") ++
      outputSchemaArgs(outputSchemaFile) ++
      Seq(prompt)

  /** Multi-turn continuation: `codex exec resume <id> <prompt>`.
    *
    * Three limitations vs. [[exec]]:
    *   - no `--cd / -C`, so cwd is set on the OS process spawn, not the argv.
    *   - no `--output-schema`, so the resumed turn's structured-output
    *     validation falls to the prompt template + post-hoc parser; the
    *     retry-with-corrective-prompt loop in `DefaultAgentCall` handles parse
    *     failures.
    *   - rejects `--sandbox <mode>` and `--full-auto` ("unexpected argument"):
    *     a resumed session inherits the sandbox it was created with. Only
    *     `--dangerously-bypass-approvals-and-sandbox` is still accepted, so
    *     [[resumeSandboxArgs]] keeps that one and drops the rest.
    *
    * codex also rejects resuming a session started with `--ephemeral`; the
    * backend never passes `--ephemeral`, so resume always finds a rollout.
    */
  def execResume(
      sessionId: WireSessionId[BackendTag.Codex.type],
      prompt: String,
      config: AgentConfig,
      mcpServerUrl: Option[String] = None
  ): Seq[String] =
    Seq("codex") ++
      mcpServerArgs(mcpServerUrl) ++
      networkConfigArgs(config) ++
      Seq("exec", "resume", "--json", WireSessionId.value(sessionId)) ++
      resumeSandboxArgs(config) ++
      CliArgs.modelArgs(config) ++
      Seq("--skip-git-repo-check") ++
      Seq(prompt)

  /** Sandbox flags accepted by `exec resume` (a subset of [[sandboxArgs]]).
    * Only `--dangerously-bypass-approvals-and-sandbox` (Full +
    * [[AutoApprove.All]]) is accepted, re-asserted each turn to keep approvals
    * off; the other tiers map to no flag since the resumed session inherits its
    * sandbox from creation.
    */
  private def resumeSandboxArgs(config: AgentConfig): Seq[String] =
    config.tools match
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All =>
            Seq("--dangerously-bypass-approvals-and-sandbox")
          case AutoApprove.Only(_) => Seq.empty
      case ToolSet.ReadOnly | ToolSet.NetworkOnly => Seq.empty

  /** Top-level `-c mcp_servers.<name>.{url,tool_timeout_sec}` overrides, placed
    * BEFORE the subcommand so they land in codex's global-config slot. The URL
    * is wrapped in TOML double-quotes since codex parses `-c` values as TOML.
    *
    * `tool_timeout_sec` extends codex's 60s per-tool default to
    * [[AskUserMcpServer.ToolTimeout]]; without it codex gives up on `ask_user`
    * after 60s and fires a duplicate follow-up.
    *
    * One of three renderings of `AskUserMcpServer.ToolTimeout` (claude JSON ms
    * / codex TOML sec / gemini settings.json ms); keep in sync.
    */
  private def mcpServerArgs(url: Option[String]): Seq[String] =
    url.toSeq.flatMap: u =>
      val timeoutSec = AskUserMcpServer.ToolTimeout.toSeconds
      Seq(
        "-c",
        s"""mcp_servers.${AskUserMcpServer.ServerName}.url="$u"""",
        "-c",
        s"mcp_servers.${AskUserMcpServer.ServerName}.tool_timeout_sec=$timeoutSec"
      )

  private def cwdArgs(workDir: os.Path): Seq[String] =
    Seq("-C", workDir.toString)

  /** codex's structured-output gate. Unlike claude, the schema is passed by
    * file path rather than inline, so the backend writes the schema string to
    * disk first and hands us the resolved path.
    */
  private def outputSchemaArgs(file: Option[os.Path]): Seq[String] =
    CliArgs.flag("--output-schema", file)(_.toString)

  /** Maps [[AgentConfig.tools]] to codex's sandbox flags (placed after the
    * `exec` subcommand). codex has no per-tool CLI allowlist, so
    * [[AutoApprove.Only]] is approximated with the coarser `--full-auto`, and
    * `NetworkOnly` has to take `workspace-write` too — codex has no
    * read-only-with-network sandbox.
    */
  private def sandboxArgs(config: AgentConfig): Seq[String] =
    config.tools match
      case ToolSet.ReadOnly    => Seq("--sandbox", "read-only")
      case ToolSet.NetworkOnly => Seq("--full-auto")
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All =>
            Seq("--dangerously-bypass-approvals-and-sandbox")
          case AutoApprove.Only(_) => Seq("--full-auto")

  /** Global `-c` override (must precede `exec` so codex reads it into top-level
    * config). On [[ToolSet.NetworkOnly]], enables network for the
    * workspace-write sandbox; off by default, so without it the planner's
    * `gh`/`curl` calls would be blocked. Empty for the other tiers.
    */
  private def networkConfigArgs(config: AgentConfig): Seq[String] =
    config.tools match
      case ToolSet.NetworkOnly =>
        Seq("-c", "sandbox_workspace_write.network_access=true")
      case ToolSet.ReadOnly | ToolSet.Full => Nil

  /** How strongly codex enforces each `(tools, autoApprove)` combination — see
    * [[sandboxArgs]] / [[networkConfigArgs]] for the flags a fresh turn gets
    * and [[resumeSandboxArgs]] for the (much smaller) set a resumed one gets.
    *
    * codex is the one backend whose answer depends on the dispatch: `exec
    * resume` accepts no sandbox flag but the bypass, so on every other tier a
    * resumed turn runs in whatever sandbox its session was created with, which
    * this classification cannot know. Each resumed cell therefore reports only
    * what orca can still stand behind for THIS turn.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = dispatch match
    case TurnDispatch.Fresh   => freshCell(tools, autoApprove)
    case TurnDispatch.Resumed => resumedCell(tools, autoApprove)

  private def freshCell(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): EnforcementCell =
    tools match
      case ToolSet.ReadOnly =>
        EnforcementCell(
          Enforcement.Hard,
          "`--sandbox read-only` blocks writes at the sandbox"
        )
      case ToolSet.NetworkOnly =>
        EnforcementCell(
          Enforcement.PromptOnly,
          "network needs the `workspace-write` sandbox, which also permits writes, so only the prompt withholds edits"
        )
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All =>
            EnforcementCell(
              Enforcement.Hard,
              "`--dangerously-bypass-approvals-and-sandbox` approves everything, which is what `All` asks for"
            )
          case AutoApprove.Only(_) =>
            EnforcementCell(
              Enforcement.SandboxApprox,
              "no per-tool allowlist, so the requested subset becomes `--full-auto`, a whole-sandbox approximation wider than what was asked"
            )

  private def resumedCell(
      tools: ToolSet,
      autoApprove: AutoApprove
  ): EnforcementCell =
    tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
        EnforcementCell(
          Enforcement.PromptOnly,
          "`exec resume` takes no sandbox flag, so the tier's restriction reaches this turn only as the read-only rule folded into its prompt"
        )
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All =>
            EnforcementCell(
              Enforcement.Hard,
              "`--dangerously-bypass-approvals-and-sandbox` is the one flag `exec resume` accepts, and it is re-asserted every turn"
            )
          case AutoApprove.Only(_) =>
            EnforcementCell(
              Enforcement.Ignored,
              "`exec resume` takes no sandbox flag, so the requested subset is encoded nowhere and the turn keeps the sandbox its session was created with"
            )
