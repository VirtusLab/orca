package orca.claude

import orca.{AutoApprove, Backend, LlmConfig, SessionId}

/** Maps LlmConfig fields to Claude Code CLI flags. `systemPrompt` is consumed
  * by the backend (written to a file whose path is passed in via
  * `systemPromptFile`); `onUnapproved` and `retrySchedule` have no CLI
  * equivalent and are handled by the orchestrator at runtime.
  */
object ClaudeArgs:

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

  def interactive(
      prompt: String,
      sessionId: SessionId[Backend.ClaudeCode.type],
      config: LlmConfig,
      systemPromptFile: Option[os.Path]
  ): Seq[String] =
    Seq("claude", prompt, "--session-id", SessionId.value(sessionId)) ++
      modelArgs(config) ++
      systemPromptFileArgs(systemPromptFile) ++
      autoApproveArgs(config)

  private def modelArgs(config: LlmConfig): Seq[String] =
    config.model.toSeq.flatMap(m => Seq("--model", m))

  private def systemPromptFileArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--append-system-prompt-file", f.toString))

  private def resumeArgs(
      resume: Option[SessionId[Backend.ClaudeCode.type]]
  ): Seq[String] =
    resume.toSeq.flatMap(id => Seq("--resume", SessionId.value(id)))

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
