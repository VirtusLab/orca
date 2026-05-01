package orca.tools.claude

import orca.{AutoApprove, Backend, LlmConfig, SessionId}

/** Maps LlmConfig fields to Claude Code CLI flags. `systemPrompt` is consumed
  * by the backend (written to a file whose path is passed in via
  * `systemPromptFile`); `onUnapproved` and `retrySchedule` have no CLI
  * equivalent and are handled by the orchestrator at runtime.
  */
object ClaudeArgs:

  /** Single-turn headless invocation: `claude -p <prompt> --output-format
    * json`. The process prints a JSON result and exits.
    */
  def headless(
      prompt: String,
      config: LlmConfig,
      systemPromptFile: Option[os.Path],
      resume: Option[SessionId[Backend.ClaudeCode.type]] = None
  ): Seq[String] =
    Seq("claude", "-p", prompt, "--output-format", "json") ++
      modelArgs(config) ++
      systemPromptFileArgs(systemPromptFile) ++
      resumeArgs(resume) ++
      autoApproveArgs(config)

  /** Stream-json interactive invocation: `claude --print --input-format
    * stream-json --output-format stream-json --verbose
    * --include-partial-messages`. The prompt goes in as the first user turn on
    * stdin; the caller writes subsequent turns and reads responses as NDJSON.
    * `--print` is required by the CLI for `--input-format stream-json` to take
    * effect — despite the name, the session runs multi-turn because the stdin
    * pipe stays open.
    */
  def streamJson(
      config: LlmConfig,
      systemPromptFile: Option[os.Path],
      resume: Option[SessionId[Backend.ClaudeCode.type]] = None,
      jsonSchema: Option[String] = None
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
      jsonSchemaArgs(jsonSchema)

  private def modelArgs(config: LlmConfig): Seq[String] =
    config.model.toSeq.flatMap(m => Seq("--model", m))

  private def systemPromptFileArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--append-system-prompt-file", f.toString))

  private def resumeArgs(
      resume: Option[SessionId[Backend.ClaudeCode.type]]
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

  private def autoApproveArgs(config: LlmConfig): Seq[String] =
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
