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
      CliArgs.modelArgs(config) ++
      systemPromptFileArgs(systemPromptFile) ++
      sessionArgs(dispatch) ++
      autoApproveArgs(config, networkTools) ++
      jsonSchemaArgs(jsonSchema) ++
      mcpConfigArgs(mcpConfig)

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
    * `Only(_)` → default permission mode plus an `--allowedTools` allowlist, so
    * only listed tools are pre-approved and everything else (edits included)
    * still prompts.
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
          // Default permission mode — nothing pre-approved except the listed
          // tools. Edits are not implicitly approved; in autonomous mode an
          // unlisted tool's prompt is auto-denied by the drain.
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
