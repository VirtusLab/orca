package orca.tools.claude

import orca.llm.{AutoApprove, BackendTag, LlmConfig, Model, SessionId}

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
      resume: Option[SessionId[BackendTag.ClaudeCode.type]] = None,
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
      modelArgs(config) ++
      systemPromptFileArgs(systemPromptFile) ++
      resumeArgs(resume) ++
      autoApproveArgs(config) ++
      jsonSchemaArgs(jsonSchema) ++
      mcpConfigArgs(mcpConfig)

  private def modelArgs(config: LlmConfig): Seq[String] =
    config.model.toSeq.flatMap(m => Seq("--model", m.name))

  private def systemPromptFileArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--append-system-prompt-file", f.toString))

  private def resumeArgs(
      resume: Option[SessionId[BackendTag.ClaudeCode.type]]
  ): Seq[String] =
    resume.toSeq.flatMap(id => Seq("--resume", SessionId.value(id)))

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

  /** `readOnly` overrides any `autoApprove` setting: claude's `--permission-mode
    * plan` makes Edit/Write/Bash unavailable to the agent (not just
    * non-auto-approved). The planner's "don't edit files" instruction in the
    * prompt is advisory; this turns it into a hard guarantee.
    */
  private def autoApproveArgs(config: LlmConfig): Seq[String] =
    if config.readOnly then Seq("--permission-mode", "plan")
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
