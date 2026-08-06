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

  /** `--model`, with the bare `haiku` alias replaced by its fully-qualified id:
    * the CLI resolves the alias, and a `claude:haiku` role pin must not land on
    * a pricier tier. Only `haiku` is rewritten — leaving
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
    * removes every name not on it: a dropped tool is absent from the `init`
    * frame and `ToolSearch` cannot resurrect it, so reviewers and planners have
    * no shell and no write primitive.
    *
    * Nothing fails if a name here goes stale — the CLI drops a name it doesn't
    * recognise without a warning, and the one check of this list against a live
    * CLI (`ClaudeIntegrationTest`) runs only under `ORCA_INTEGRATION`.
    */
  private[claude] val ReadOnlyTools: Seq[String] =
    Seq("Read", "Grep", "Glob", "Skill")

  /** Maps [[AgentConfig.tools]] to claude's tool and permission flags.
    *
    * Both read-only tiers pass `--tools` (see [[ReadOnlyTools]]); `NetworkOnly`
    * appends `networkTools` to it. Both also [[approve]] what they must be able
    * to call: `mcpTools`, plus `networkTools` on `NetworkOnly`. These tiers
    * ignore [[AgentConfig.autoApprove]], so an MCP tool such a turn is meant to
    * use has to arrive through `mcpTools`.
    *
    * `Full` follows [[AgentConfig.autoApprove]]: `All` → `bypassPermissions`;
    * `Only(_)` → default permission mode plus `--allowedTools`. Unlike
    * `--tools`, that allowlist adds to claude's defaults rather than confining
    * the agent to the names listed.
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
        Seq("--tools", ReadOnlyTools.mkString(",")) ++ approve(mcpTools)
      case ToolSet.NetworkOnly =>
        Seq("--tools", (ReadOnlyTools ++ networkTools).mkString(",")) ++
          approve(mcpTools ++ networkTools)
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All =>
            Seq("--permission-mode", "bypassPermissions")
          // Nothing pre-approved beyond claude's own defaults.
          case AutoApprove.Only(tools) if tools.isEmpty => Seq.empty
          case AutoApprove.Only(tools) =>
            Seq("--allowedTools", tools.toSeq.sorted.mkString(","))

  /** Whether a tier's `--tools` list withholds `Bash`, and so needs the host to
    * hand back the reads it would otherwise shell out for. Matched exhaustively
    * so a new `ToolSet` case has to answer the question here rather than
    * silently defaulting.
    */
  private[claude] def losesShell(tools: ToolSet): Boolean = tools match
    case ToolSet.ReadOnly | ToolSet.NetworkOnly => true
    case ToolSet.Full                           => false

  /** Grants `tools` on a read-only turn. `--tools` only advertises: the turn
    * stays in the default permission mode, where `WebFetch` and MCP tools are
    * gated, and stdin is closed under `--print`, so an ungranted call comes
    * back as a failed `tool_result`.
    *
    * One flag carrying one list. Whether claude honours a repeated
    * `--allowedTools` is unverified, so a caller with more to grant passes the
    * union here rather than calling twice.
    */
  private def approve(tools: Seq[String]): Seq[String] =
    if tools.isEmpty then Seq.empty
    else Seq("--allowedTools", tools.mkString(","))

  /** How strongly claude enforces each `(tools, autoApprove)` combination — see
    * [[permissionArgs]] for the flags this classifies. Every one of them is a
    * mechanical gate, hence `Hard` throughout.
    *
    * Written as an exhaustive match rather than a bare constant so a future
    * `ToolSet`/`AutoApprove` case fails compilation here.
    */
  def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
    tools match
      case ToolSet.ReadOnly | ToolSet.NetworkOnly => Enforcement.Hard
      case ToolSet.Full =>
        autoApprove match
          case AutoApprove.All     => Enforcement.Hard
          case AutoApprove.Only(_) => Enforcement.Hard
