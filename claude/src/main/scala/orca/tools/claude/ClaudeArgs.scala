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
      networkTools: Seq[String] = Seq.empty
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
      autoApproveArgs(config, networkTools) ++
      jsonSchemaArgs(jsonSchema) ++
      mcpConfigArgs(mcpConfig)

  /** `--model`, with the bare `haiku` alias replaced by its fully-qualified id.
    *
    * Under `--permission-mode plan` (every read-only turn — see
    * `autoApproveArgs`) the CLI serves `claude-sonnet-5` for `--model haiku`,
    * 3x the intended rate for a `claude:haiku` role pin. Its init line still
    * names haiku, so re-checking this means reading the model off the assistant
    * messages or the result's `modelUsage`, not off the init line. It honours
    * `claude-haiku-4-5` in the same mode; verified against claude 2.1.220.
    *
    * Only `haiku` is rewritten — the CLI resolves `sonnet`/`opus`/`fable`
    * correctly in plan mode, and leaving those bare keeps them tracking the
    * latest model in their tier.
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

  /** Maps [[AgentConfig.tools]] to claude's permission flags. Both read-only
    * tiers use `--permission-mode plan`, which makes Edit/Write/Bash
    * unavailable (not just non-auto-approved) — a hard no-edit guarantee.
    *
    * `NetworkOnly` additionally pre-approves `networkTools` via
    * `--allowedTools`, layering read-only network access onto plan mode. The
    * list is command-scoped, so plan mode still blocks general bash and every
    * edit; an empty list leaves plain plan mode.
    *
    * `Full` follows [[AgentConfig.autoApprove]]: `All` → `bypassPermissions`;
    * `Only(_)` → default permission mode plus `--allowedTools`. The allowlist
    * adds to claude's defaults; it does not restrict the agent to the listed
    * tools. Under `--print` nothing is prompted back to orca: a call the CLI
    * won't run comes back as a failed `tool_result` (pinned by
    * `ClaudeIntegrationTest`).
    */
  private def autoApproveArgs(
      config: AgentConfig,
      networkTools: Seq[String]
  ): Seq[String] =
    config.tools match
      case ToolSet.ReadOnly => Seq("--permission-mode", "plan")
      case ToolSet.NetworkOnly if networkTools.isEmpty =>
        Seq("--permission-mode", "plan")
      case ToolSet.NetworkOnly =>
        Seq(
          "--permission-mode",
          "plan",
          "--allowedTools",
          networkTools.mkString(",")
        )
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All =>
            Seq("--permission-mode", "bypassPermissions")
          // Nothing pre-approved beyond claude's own defaults.
          case AutoApprove.Only(tools) if tools.isEmpty => Seq.empty
          case AutoApprove.Only(tools) =>
            Seq("--allowedTools", tools.toSeq.sorted.mkString(","))

  /** How strongly claude enforces each `(tools, autoApprove)` combination — see
    * [[autoApproveArgs]] for the flags this classifies. Every combination is
    * `Hard`: plan mode makes edits/shell mechanically unavailable, and every
    * `--allowedTools`/`bypassPermissions` variant is a mechanical per-tool
    * gate.
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
