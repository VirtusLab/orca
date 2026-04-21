package orca.claude

import orca.{AutoApprove, LlmConfig}

object ClaudeArgs:

  def headless(
      prompt: String,
      config: LlmConfig,
      systemPromptFile: Option[os.Path]
  ): Seq[String] =
    Seq("claude", "-p", prompt, "--output-format", "json") ++
      modelArgs(config) ++
      systemPromptFileArgs(systemPromptFile) ++
      autoApproveArgs(config)

  private def modelArgs(config: LlmConfig): Seq[String] =
    config.model.toSeq.flatMap(m => Seq("--model", m))

  private def systemPromptFileArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--append-system-prompt-file", f.toString))

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
