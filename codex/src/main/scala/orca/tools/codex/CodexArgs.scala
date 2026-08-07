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
    *   - rejects `--sandbox <mode>` ("unexpected argument"), which is why the
    *     tier's sandbox is re-applied through [[resumeSandboxModeArgs]]' `-c`
    *     override instead. `--full-auto` is accepted but deprecated, and
    *     omitted for that reason rather than because resume refuses it.
    *
    * A resumed session INHERITS the sandbox it was created with — probed
    * 2026-08-07 against codex-cli 0.145.0: a flagless resume of a `--full-auto`
    * session wrote a file, while a flagless fresh turn was blocked read-only.
    * So the tier has to be re-asserted per turn; without that, a session
    * created `NetworkOnly` and resumed `ReadOnly` (which `Plan.reviewed` does)
    * would keep workspace write.
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
      resumeSandboxModeArgs(config) ++
      networkConfigArgs(config) ++
      Seq("exec", "resume", "--json", WireSessionId.value(sessionId)) ++
      resumeSandboxArgs(config) ++
      CliArgs.modelArgs(config) ++
      Seq("--skip-git-repo-check") ++
      Seq(prompt)

  /** Re-applies the read-only tiers' sandbox on a resumed turn, through the
    * global `-c` slot `exec resume` does accept — the `--sandbox` flag it
    * doesn't. Verified to narrow an already-widened session: resuming a
    * `--full-auto` session with `-c sandbox_mode="read-only"` blocked the write
    * (probed 2026-08-07, codex-cli 0.145.0).
    *
    * `Full` is absent because its two shapes are already handled after the
    * subcommand — [[AutoApprove.All]] by the bypass flag in
    * [[resumeSandboxArgs]], and `Only` by neither, matching what a resumed
    * `Only` turn can be held to.
    */
  private def resumeSandboxModeArgs(config: AgentConfig): Seq[String] =
    // Values are TOML, hence the embedded quotes.
    config.tools match
      case ToolSet.ReadOnly    => Seq("-c", "sandbox_mode=\"read-only\"")
      case ToolSet.NetworkOnly => Seq("-c", "sandbox_mode=\"workspace-write\"")
      case ToolSet.Full        => Nil

  /** Sandbox flags accepted by `exec resume` AFTER the subcommand (a subset of
    * [[sandboxArgs]]): only `--dangerously-bypass-approvals-and-sandbox` (Full
    * + [[AutoApprove.All]]), re-asserted each turn to keep approvals off. The
    * read-only tiers go through [[resumeSandboxModeArgs]] instead.
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
    * [[sandboxArgs]] / [[networkConfigArgs]] for the flags a fresh turn gets,
    * and [[resumeSandboxModeArgs]] / [[resumeSandboxArgs]] for a resumed one.
    *
    * codex is the one backend whose answer still depends on the dispatch, and
    * now in one cell only: the read-only tiers get their sandbox re-applied per
    * turn, so they answer the same either way, while a resumed `Full` +
    * [[AutoApprove.Only]] turn keeps whatever sandbox its session was created
    * with — which this classification cannot know.
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
          "the `read-only` sandbox blocks writes, and a resumed turn re-applies it rather than inheriting the session's (probed 2026-08-07, codex-cli 0.145.0)"
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
      // The read-only tiers answer exactly as a fresh turn does, because
      // `resumeSandboxModeArgs` re-applies the same sandbox.
      case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
        freshCell(tools, autoApprove)
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All =>
            EnforcementCell(
              Enforcement.Hard,
              "`--dangerously-bypass-approvals-and-sandbox` is re-asserted on every resumed turn, and `exec resume --help` lists it (probed 2026-08-07, codex-cli 0.145.0)"
            )
          case AutoApprove.Only(_) =>
            EnforcementCell(
              Enforcement.Ignored,
              "the requested subset has no sandbox of its own to re-apply, so a resumed turn keeps whichever sandbox its session was created with — inheritance confirmed by probing a flagless resume of a `--full-auto` session (2026-08-07, codex-cli 0.145.0)"
            )
