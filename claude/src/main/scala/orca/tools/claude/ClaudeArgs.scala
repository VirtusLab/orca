package orca.tools.claude

import orca.backend.{CliArgs, Dispatch}
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Enforcement,
  WireSessionId,
  ToolSet
}

/** Maps AgentConfig fields to Claude Code CLI flags. `systemPrompt` is consumed
  * by the backend (written to a file whose path is passed in via
  * `systemPromptFile`); `onUnapproved` and `retrySchedule` have no CLI
  * equivalent and are handled by the orchestrator at runtime.
  */
private[claude] object ClaudeArgs:

  /** Stream-json invocation: `claude --print --input-format stream-json
    * --output-format stream-json --verbose --include-partial-messages`. Used by
    * both the autonomous and interactive paths — they only differ in whether
    * the `--mcp-config` arg (and the `ask_user` tool it wires) is present.
    *
    * `--print` is required by the CLI for `--input-format stream-json` to take
    * effect.
    */
  def streamJson(
      config: AgentConfig,
      systemPromptFile: Option[os.Path],
      dispatch: Dispatch[BackendTag.ClaudeCode.type],
      jsonSchema: Option[String] = None,
      mcpConfig: Option[os.Path] = None,
      networkTools: Seq[String] = Seq.empty,
      mcpTools: Seq[String] = Seq.empty
  ): Seq[String] =
    Seq(
      "claude",
      "--print",
      "--input-format",
      "stream-json",
      "--output-format",
      "stream-json",
      "--verbose",
      "--include-partial-messages"
    ) ++
      modelArgs(config) ++
      systemPromptFileArgs(systemPromptFile) ++
      sessionArgs(dispatch) ++
      permissionArgs(config, networkTools, mcpTools) ++
      jsonSchemaArgs(jsonSchema) ++
      mcpConfigArgs(mcpConfig)

  /** `--model`, with the bare `haiku` alias replaced by its fully-qualified id.
    *
    * Under `--permission-mode plan` the CLI served `claude-sonnet-5` for
    * `--model haiku` — 3x the intended rate for a `claude:haiku` role pin
    * (measured on 2.1.220). Measured fixed on 2.1.222, but the qualified id is
    * the cheaper failure mode. Only `haiku` is rewritten; leaving
    * `sonnet`/`opus`/`fable` bare keeps them tracking their tier's latest.
    */
  private def modelArgs(config: AgentConfig): Seq[String] =
    CliArgs.flag("--model", config.model): model =>
      if model.name == "haiku" then DefaultClaudeAgent.Haiku.name
      else model.name

  private def systemPromptFileArgs(file: Option[os.Path]): Seq[String] =
    CliArgs.flag("--append-system-prompt-file", file)(_.toString)

  /** Fresh dispatch → `--session-id <uuid>` (creates the session with our
    * pre-allocated UUID). Resume → `--resume <uuid>` (claude refuses to reuse
    * `--session-id` once the session exists).
    */
  private def sessionArgs(
      dispatch: Dispatch[BackendTag.ClaudeCode.type]
  ): Seq[String] = dispatch match
    case Dispatch.Fresh(Some(id)) =>
      Seq("--session-id", WireSessionId.value(id))
    // Unreachable: claude is `IdScheme.ClientClaimed`, which always supplies the
    // claim id on a fresh dispatch. A defensive error rather than a silent
    // fallback, so a future scheme-wiring mistake fails loudly.
    case Dispatch.Fresh(None) =>
      throw new IllegalStateException(
        "claude's ClientClaimed scheme must supply a fresh claim id"
      )
    case Dispatch.Resume(id) => Seq("--resume", WireSessionId.value(id))

  /** claude's CLI only accepts `--json-schema <inline>` — there's no
    * `--json-schema-file` form. Typical Orca schemas (a few KB) inline fine
    * within `ARG_MAX`; a pathologically large schema fails the exec loudly.
    */
  private def jsonSchemaArgs(schema: Option[String]): Seq[String] =
    CliArgs.flag("--json-schema", schema)(identity)

  private def mcpConfigArgs(file: Option[os.Path]): Seq[String] =
    CliArgs.flag("--mcp-config", file)(_.toString)

  /** Built-in tools a read-only turn keeps. `--tools` is an allowlist that
    * removes every name not on it: the dropped tools are absent from the `init`
    * frame and `ToolSearch` cannot resurrect them, so reviewers and planners
    * have no shell and no write primitive.
    *
    * **It does not survive `--resume`.** Measured on 2.1.222: resuming a
    * session created under this list, without re-passing `--tools`, brings back
    * the full default set — `Bash`, `Edit`, `Write` and all. [[streamJson]]
    * rebuilds the flags from each turn's own config, which is what keeps a
    * resumed reviewer restricted; a resume path that reused stored args would
    * not.
    *
    * Unknown names are dropped silently (`Read,Grep,NoSuchTool` yields
    * `Grep,Read`, exit 0, no warning), so this list is pinned against a live
    * CLI by `ClaudeIntegrationTest`.
    */
  private[claude] val ReadOnlyTools: Seq[String] =
    Seq("Read", "Grep", "Glob", "Skill")

  /** Maps [[AgentConfig.tools]] to claude's tool and permission flags.
    *
    * Both read-only tiers pass `--tools` (see [[ReadOnlyTools]]); `NetworkOnly`
    * appends `networkTools` to it. Not `--permission-mode plan`: that removed
    * no tools at all (`docs/research/run-cost/09-diff-vs-coordinates.md` §2).
    *
    * `--tools` is not a complete boundary: MCP tools pass through it
    * unfiltered. Measured on claude 2.1.222 — an `init` frame under `--tools
    * Read,Grep,Glob,Skill` still carried the `mcp__…` tools of an installed
    * server, so an MCP server that can write is not covered by this allowlist
    * at all. Advertised is not callable, though: the read-only tiers ignore
    * [[AgentConfig.autoApprove]], so an MCP tool they are meant to use must be
    * named in `mcpTools`, which they pre-approve via `--allowedTools`. An
    * un-named one comes back as a failed `tool_result` rather than prompting.
    *
    * `Full` follows [[AgentConfig.autoApprove]]: `All` → `bypassPermissions`;
    * `Only(_)` → default permission mode plus `--allowedTools`. The allowlist
    * adds to claude's defaults; it does not restrict the agent to the listed
    * tools. Under `--print` nothing is prompted back to orca: a call the CLI
    * won't run comes back as a failed `tool_result` (pinned by
    * `ClaudeIntegrationTest`).
    *
    * Both flags are variadic (`--tools <tools...>`), so nothing positional may
    * follow them in the argv — [[streamJson]] emits only flag-value pairs after
    * this, and the prompt goes over stdin.
    */
  private def permissionArgs(
      config: AgentConfig,
      networkTools: Seq[String],
      mcpTools: Seq[String]
  ): Seq[String] =
    config.tools match
      case ToolSet.ReadOnly =>
        Seq("--tools", ReadOnlyTools.mkString(",")) ++ approveMcp(mcpTools)
      case ToolSet.NetworkOnly =>
        Seq("--tools", (ReadOnlyTools ++ networkTools).mkString(",")) ++
          approveMcp(mcpTools)
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All =>
            Seq("--permission-mode", "bypassPermissions")
          // Nothing pre-approved beyond claude's own defaults.
          case AutoApprove.Only(tools) if tools.isEmpty => Seq.empty
          case AutoApprove.Only(tools) =>
            Seq("--allowedTools", tools.toSeq.sorted.mkString(","))

  private def approveMcp(mcpTools: Seq[String]): Seq[String] =
    if mcpTools.isEmpty then Nil
    else Seq("--allowedTools", mcpTools.mkString(","))

  /** How strongly claude enforces each `(tools, autoApprove)` combination — see
    * [[permissionArgs]] for the flags this classifies. Every combination is
    * `Hard`. The read-only tiers rest on `--tools`, which removes every
    * unlisted built-in; the `--allowedTools`/`bypassPermissions` variants are
    * mechanical per-tool gates.
    *
    * Written as an exhaustive match (all arms `Hard`) rather than a bare
    * constant so a future `ToolSet`/`AutoApprove` case fails compilation here.
    */
  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
    tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly => Enforcement.Hard
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All     => Enforcement.Hard
          case AutoApprove.Only(_) => Enforcement.Hard
