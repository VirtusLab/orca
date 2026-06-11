package orca.tools.claude

import orca.backend.{CliArgs, Dispatch}
import orca.llm.{AutoApprove, BackendTag, LlmConfig, SessionId}

/** Maps LlmConfig fields to Claude Code CLI flags. `systemPrompt` is consumed
  * by the backend (written to a file whose path is passed in via
  * `systemPromptFile`); `onUnapproved` and `retrySchedule` have no CLI
  * equivalent and are handled by the orchestrator at runtime.
  */
private[claude] object ClaudeArgs:

  /** Stream-json invocation: `claude --print --input-format stream-json
    * --output-format stream-json --verbose --include-partial-messages`. Used by
    * both the autonomous and interactive paths — they only differ in whether
    * the `--mcp-config` arg (and the `ask_user` tool that comes with it) is
    * wired. The prompt goes in as the first user turn on stdin; for single-turn
    * (autonomous) calls the backend closes stdin immediately, for multi-turn
    * (interactive) it stays open.
    *
    * `--print` is required by the CLI for `--input-format stream-json` to take
    * effect — despite the name, the session runs multi-turn because stdin can
    * stay open.
    */
  def streamJson(
      config: LlmConfig,
      systemPromptFile: Option[os.Path],
      dispatch: Dispatch[BackendTag.ClaudeCode.type],
      jsonSchema: Option[String] = None,
      mcpConfig: Option[os.Path] = None
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
      autoApproveArgs(config) ++
      jsonSchemaArgs(jsonSchema) ++
      mcpConfigArgs(mcpConfig)

  private def systemPromptFileArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--append-system-prompt-file", f.toString))

  /** Fresh dispatch → `--session-id <uuid>` (creates the session with our
    * pre-allocated UUID). Resume → `--resume <uuid>` (claude refuses to reuse
    * `--session-id` once the session exists).
    */
  private def sessionArgs(
      dispatch: Dispatch[BackendTag.ClaudeCode.type]
  ): Seq[String] = dispatch match
    case Dispatch.Fresh(id)  => Seq("--session-id", SessionId.value(id))
    case Dispatch.Resume(id) => Seq("--resume", SessionId.value(id))

  /** claude's CLI only accepts `--json-schema <inline>` — there's no
    * `--json-schema-file` form. For typical Orca schemas (a few KB) inlining is
    * fine; `ARG_MAX` gives us ~128KB of headroom on Linux and ~256KB of total
    * argv on macOS. If someone builds a flow with a schema that large, the exec
    * will fail loudly.
    */
  private def jsonSchemaArgs(schema: Option[String]): Seq[String] =
    schema.toSeq.flatMap(s => Seq("--json-schema", s))

  private def mcpConfigArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--mcp-config", f.toString))

  /** Read-only network/GitHub tools pre-approved for planning turns. Plan mode
    * blocks these behind a permission prompt; layering them via `--allowedTools`
    * lets an autonomous planner (no one to answer prompts) fetch issues, PRs,
    * and web pages. Scoped to non-mutating commands only — `gh api` and bare
    * `curl` are excluded because they can also write.
    */
  private val readOnlyNetworkTools: Seq[String] = Seq(
    "WebFetch",
    "WebSearch",
    "Bash(gh issue view:*)",
    "Bash(gh pr view:*)",
    "Bash(gh search:*)",
    "Bash(gh repo view:*)"
  )

  /** `readOnly` overrides any `autoApprove` setting: claude's
    * `--permission-mode plan` makes Edit/Write/Bash unavailable to the agent
    * (not just non-auto-approved). The planner's "don't edit files" instruction
    * in the prompt is advisory; this turns it into a hard guarantee.
    *
    * Plan mode also gates read-only network exploration (WebFetch, and
    * `gh`/`curl` via Bash) behind a permission prompt — harmless interactively,
    * but an autonomous planner has no one to answer it, so every issue/PR fetch
    * dies. [[readOnlyNetworkTools]] is layered on via `--allowedTools` so the
    * planner can read from GitHub and the web while plan mode still hard-blocks
    * every edit.
    */
  private def autoApproveArgs(config: LlmConfig): Seq[String] =
    if config.readOnly then
      Seq(
        "--permission-mode",
        "plan",
        "--allowedTools",
        readOnlyNetworkTools.mkString(",")
      )
    else
      config.autoApprove match
        case AutoApprove.All =>
          Seq("--permission-mode", "bypassPermissions")
        case AutoApprove.Only(tools) if tools.isEmpty =>
          Seq("--permission-mode", "acceptEdits")
        case AutoApprove.Only(tools) =>
          Seq(
            "--permission-mode",
            "acceptEdits",
            "--allowedTools",
            tools.toSeq.sorted.mkString(",")
          )
