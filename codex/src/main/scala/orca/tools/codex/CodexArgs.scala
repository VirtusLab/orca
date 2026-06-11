package orca.tools.codex

import orca.backend.CliArgs
import orca.backend.mcp.AskUserMcpServer
import orca.llm.{AutoApprove, BackendTag, LlmConfig, SessionId, ToolSet}

/** Maps `LlmConfig` fields to `codex exec` CLI flags. `systemPrompt` is not
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
      config: LlmConfig,
      outputSchemaFile: Option[os.Path],
      workDir: os.Path,
      mcpServerUrl: Option[String] = None
  ): Seq[String] =
    Seq("codex") ++
      mcpServerArgs(mcpServerUrl) ++
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
    * Two limitations vs. [[exec]]:
    *   - `exec resume` doesn't accept `--cd / -C`, so cwd is set on the OS
    *     process spawn rather than the argv.
    *   - `exec resume` doesn't accept `--output-schema`, so the resumed turn's
    *     structured-output validation falls to the prompt template + the
    *     post-hoc parser. The retry-with- corrective-prompt loop in
    *     `DefaultLlmCall` handles parse failures.
    *
    * codex also enforces that the resumed session was not started with
    * `--ephemeral`; the backend never passes `--ephemeral` on `exec`, so resume
    * always finds a rollout.
    */
  def execResume(
      sessionId: SessionId[BackendTag.Codex.type],
      prompt: String,
      config: LlmConfig,
      mcpServerUrl: Option[String] = None
  ): Seq[String] =
    Seq("codex") ++
      mcpServerArgs(mcpServerUrl) ++
      Seq("exec", "resume", "--json", SessionId.value(sessionId)) ++
      sandboxArgs(config) ++
      CliArgs.modelArgs(config) ++
      Seq("--skip-git-repo-check") ++
      Seq(prompt)

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

  /** Maps [[LlmConfig.tools]] to codex's sandbox flags. The read-only tiers use
    * `--sandbox read-only` (no writes, no shell side-effects), matching
    * claude's `--permission-mode plan`. `Full` follows
    * [[LlmConfig.autoApprove]]; codex has no per-tool CLI allowlist, so
    * [[AutoApprove.Only]] is approximated with `--full-auto` (sandboxed
    * automatic execution).
    *
    *   - `ReadOnly` / `NetworkOnly` → `--sandbox read-only`
    *   - `Full` + `AutoApprove.All` →
    *     `--dangerously-bypass-approvals-and-sandbox`
    *   - `Full` + `AutoApprove.Only(_)` → `--full-auto`
    *
    * `NetworkOnly` is read-only here too; the network-capable variant (codex
    * needs `--sandbox workspace-write` for network, which also permits writes)
    * is layered on in a later step.
    */
  private def sandboxArgs(config: LlmConfig): Seq[String] =
    config.tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
        Seq("--sandbox", "read-only")
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All =>
            Seq("--dangerously-bypass-approvals-and-sandbox")
          case AutoApprove.Only(_) => Seq("--full-auto")
