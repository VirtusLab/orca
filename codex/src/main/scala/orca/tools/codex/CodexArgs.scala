package orca.tools.codex

import orca.backend.CliArgs
import orca.backend.mcp.AskUserMcpServer
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  WireSessionId,
  ToolSet
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
      // --skip-git-repo-check is permissive — codex bails if it can't
      // tell whether cwd is a git repo, which is a poor fit for tests
      // and one-off invocations against arbitrary directories.
      Seq("--skip-git-repo-check") ++
      outputSchemaArgs(outputSchemaFile) ++
      Seq(prompt)

  /** Multi-turn continuation: `codex exec resume <id> <prompt>`.
    *
    * Three limitations vs. [[exec]]:
    *   - `exec resume` doesn't accept `--cd / -C`, so cwd is set on the OS
    *     process spawn rather than the argv.
    *   - `exec resume` doesn't accept `--output-schema`, so the resumed turn's
    *     structured-output validation falls to the prompt template + the
    *     post-hoc parser. The retry-with- corrective-prompt loop in
    *     `DefaultAgentCall` handles parse failures.
    *   - `exec resume` rejects `--sandbox <mode>` and `--full-auto` (it errors
    *     with "unexpected argument"): a resumed session inherits the sandbox it
    *     was created with. Only `--dangerously-bypass-approvals-and-sandbox` is
    *     still accepted, so [[resumeSandboxArgs]] keeps that one and drops the
    *     rest. Without this, every resumed turn (a fix iteration, a follow-up
    *     task on the same session, or a cross-process resume) fails.
    *
    * codex also enforces that the resumed session was not started with
    * `--ephemeral`; the backend never passes `--ephemeral` on `exec`, so resume
    * always finds a rollout.
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

  /** Sandbox flags accepted by `exec resume` (a subset of [[sandboxArgs]]). The
    * resumed session inherits its sandbox from creation, and `exec resume`
    * rejects `--sandbox <mode>` / `--full-auto` outright, so those map to no
    * flag here. Only `--dangerously-bypass-approvals-and-sandbox` (Full +
    * [[AutoApprove.All]]) is accepted and is re-asserted each turn to keep
    * approvals off.
    */
  private def resumeSandboxArgs(config: AgentConfig): Seq[String] =
    config.tools match
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All =>
            Seq("--dangerously-bypass-approvals-and-sandbox")
          case AutoApprove.Only(_) => Seq.empty
      case ToolSet.ReadOnly | ToolSet.NetworkOnly => Seq.empty

  /** Top-level `-c mcp_servers.<name>.{url,tool_timeout_sec}` overrides. Placed
    * BEFORE the subcommand so they land in codex's global-config slot (the
    * subcommand inherits them). URL value is wrapped in TOML double-quotes
    * since codex parses `-c` values as TOML literals.
    *
    * The `tool_timeout_sec` override extends codex's per-tool timeout from its
    * 60s default to [[AskUserMcpServer.ToolTimeout]]. Without it, codex gives
    * up on `ask_user` after 60s and fires a follow-up — the user ends up
    * answering twice.
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
    file.toSeq.flatMap(p => Seq("--output-schema", p.toString))

  /** Maps [[AgentConfig.tools]] to codex's sandbox flags (placed after the
    * `exec` subcommand). `ReadOnly` uses `--sandbox read-only` (no writes, no
    * shell side-effects), matching claude's `--permission-mode plan`. `Full`
    * follows [[AgentConfig.autoApprove]]; codex has no per-tool CLI allowlist,
    * so [[AutoApprove.Only]] is approximated with `--full-auto`.
    *
    *   - `ReadOnly` → `--sandbox read-only`
    *   - `NetworkOnly` → `--full-auto` (workspace-write + non-interactive
    *     approval), paired with the network override in [[networkConfigArgs]]
    *   - `Full` + `AutoApprove.All` →
    *     `--dangerously-bypass-approvals-and-sandbox`
    *   - `Full` + `AutoApprove.Only(_)` → `--full-auto`
    *
    * `NetworkOnly` has no read-only-with-network sandbox on codex: network
    * needs `workspace-write`, which also permits workspace writes, so the
    * no-edit guarantee there is prompt-only (the planning prompts forbid
    * edits).
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

  /** Global `-c` overrides that must precede the `exec` subcommand (codex reads
    * them into its top-level config, which `exec` inherits). On
    * [[ToolSet.NetworkOnly]], enable network for the workspace-write sandbox
    * that [[sandboxArgs]] selects via `--full-auto`; off by default, so without
    * this the planner's `gh`/`curl` calls would be blocked. Empty for the other
    * tiers.
    */
  private def networkConfigArgs(config: AgentConfig): Seq[String] =
    config.tools match
      case ToolSet.NetworkOnly =>
        Seq("-c", "sandbox_workspace_write.network_access=true")
      case ToolSet.ReadOnly | ToolSet.Full => Nil
