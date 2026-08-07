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
    val wiring =
      sandboxWiring(config.tools, config.autoApprove, TurnDispatch.Fresh)
    Seq("codex") ++
      mcpServerArgs(mcpServerUrl) ++
      wiring.preSubcommand ++
      Seq("exec", "--json") ++
      wiring.postSubcommand ++
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
    *     tier's sandbox is re-applied through [[sandboxWiring]]'s `-c` override
    *     instead.
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
    val wiring =
      sandboxWiring(config.tools, config.autoApprove, TurnDispatch.Resumed)
    Seq("codex") ++
      mcpServerArgs(mcpServerUrl) ++
      wiring.preSubcommand ++
      Seq("exec", "resume", "--json", WireSessionId.value(sessionId)) ++
      wiring.postSubcommand ++
      CliArgs.modelArgs(config) ++
      Seq("--skip-git-repo-check") ++
      Seq(prompt)

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

  /** The sandbox flags a tier gets and the guarantee they achieve — one match
    * builds both, so they cannot drift apart.
    *
    * @param preSubcommand
    *   global `-c` overrides, which must precede `exec` for codex to read them
    *   into top-level config.
    * @param postSubcommand
    *   sandbox flags, which go after the subcommand.
    */
  private case class SandboxWiring(
      preSubcommand: Seq[String],
      postSubcommand: Seq[String],
      cell: EnforcementCell
  )

  /** codex's isolation wiring for one `(tools, autoApprove, dispatch)`: which
    * flags [[exec]] / [[execResume]] emit, and how strongly they hold.
    *
    * codex is the one backend whose answer depends on the dispatch, in one cell
    * only: the read-only tiers get their sandbox re-applied per turn, so they
    * answer the same either way, while a resumed `Full` + [[AutoApprove.Only]]
    * turn keeps whatever sandbox its session was created with.
    *
    * Two probes, both 2026-08-07 against codex-cli 0.145.0, decide the resume
    * arms. A resumed session INHERITS the sandbox it was created with: a
    * flagless resume of a `--full-auto` session wrote a file, while a flagless
    * fresh turn was blocked read-only. Re-applying narrows it back: resuming
    * that session with `-c sandbox_mode="read-only"` blocked the write. Without
    * the re-application a session created `NetworkOnly` and resumed `ReadOnly`
    * (which `Plan.reviewed` does) would keep workspace write.
    *
    * The fresh arms spell `workspace-write` out rather than using
    * `--full-auto`, which the CLI now warns is deprecated in favour of exactly
    * that flag. Both spellings were probed identical under `exec` (2026-08-07,
    * codex-cli 0.145.0): `approval: never`, `sandbox: workspace-write`.
    */
  private def sandboxWiring(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): SandboxWiring =
    // `-c` values are TOML, hence the embedded quotes.
    tools match
      case ToolSet.ReadOnly =>
        val (pre, post) = dispatch match
          case TurnDispatch.Fresh => (Nil, Seq("--sandbox", "read-only"))
          case TurnDispatch.Resumed =>
            (Seq("-c", "sandbox_mode=\"read-only\""), Nil)
        SandboxWiring(
          pre,
          post,
          EnforcementCell(
            Enforcement.Hard,
            "the `read-only` sandbox blocks writes, and a resumed turn re-applies it rather than inheriting the session's (probed 2026-08-07, codex-cli 0.145.0)"
          )
        )
      // codex has no read-only-with-network sandbox, so network costs the tier
      // its `workspace-write` sandbox; the `-c` override turns network on,
      // since workspace-write blocks it by default.
      case ToolSet.NetworkOnly =>
        val network = Seq("-c", "sandbox_workspace_write.network_access=true")
        val (pre, post) = dispatch match
          case TurnDispatch.Fresh =>
            (network, Seq("--sandbox", "workspace-write"))
          case TurnDispatch.Resumed =>
            (Seq("-c", "sandbox_mode=\"workspace-write\"") ++ network, Nil)
        SandboxWiring(
          pre,
          post,
          EnforcementCell(
            Enforcement.PromptOnly,
            "network needs the `workspace-write` sandbox, which also permits writes, so only the prompt withholds edits"
          )
        )
      case ToolSet.Full =>
        autoApprove match
          // The one sandbox flag `exec resume` also accepts, so it rides on
          // both dispatches.
          case AutoApprove.All =>
            SandboxWiring(
              Nil,
              Seq("--dangerously-bypass-approvals-and-sandbox"),
              dispatch match
                case TurnDispatch.Fresh =>
                  EnforcementCell(
                    Enforcement.Hard,
                    "`--dangerously-bypass-approvals-and-sandbox` approves everything, which is what `All` asks for"
                  )
                case TurnDispatch.Resumed =>
                  EnforcementCell(
                    Enforcement.Hard,
                    "`--dangerously-bypass-approvals-and-sandbox` is re-asserted on every resumed turn, and `exec resume --help` lists it (probed 2026-08-07, codex-cli 0.145.0)"
                  )
            )
          // codex has no per-tool CLI allowlist, so the requested subset is
          // approximated by a whole-workspace sandbox — one a resumed turn has
          // no `-c` shape for, leaving it with the session's own.
          case AutoApprove.Only(_) =>
            dispatch match
              case TurnDispatch.Fresh =>
                SandboxWiring(
                  Nil,
                  Seq("--sandbox", "workspace-write"),
                  EnforcementCell(
                    Enforcement.SandboxApprox,
                    "no per-tool allowlist, so the requested subset becomes `--sandbox workspace-write`, a whole-sandbox approximation wider than what was asked"
                  )
                )
              case TurnDispatch.Resumed =>
                SandboxWiring(
                  Nil,
                  Nil,
                  EnforcementCell(
                    Enforcement.Ignored,
                    "the requested subset has no sandbox of its own to re-apply, so a resumed turn keeps whichever sandbox its session was created with (probed 2026-08-07, codex-cli 0.145.0)"
                  )
                )

  /** How strongly codex enforces each `(tools, autoApprove)` combination on a
    * `dispatch` turn — see [[sandboxWiring]], which derives this from the same
    * match that builds the flags.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = sandboxWiring(tools, autoApprove, dispatch).cell
