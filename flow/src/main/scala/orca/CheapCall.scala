package orca

import orca.llm.{BackendTag, LlmTool}

import scala.util.control.NonFatal

extension [B <: BackendTag](llm: LlmTool[B])
  /** Best-effort one-line reply from the cheap model. Runs `prompt` on
    * `llm.cheap.withReadOnly` (no prompt echo), and returns the first non-blank
    * line trimmed — or `fallback` if the reply is empty or the call fails for
    * any non-fatal reason.
    *
    * Markdown code-fence lines (```` ``` ````) are skipped: the cheap model
    * sometimes wraps its one-line reply in a fenced block, and we must not
    * return a literal ```` ``` ```` as a commit message or branch label.
    *
    * Never throws: incidental cheap-model calls (branch naming, default commit
    * messages) must never break a flow. Requires `InStage` because it is a
    * gated LLM call.
    */
  def cheapOneShot(prompt: String, fallback: => String)(using InStage): String =
    try
      val (_, text) =
        llm.cheap.withReadOnly.autonomous.run(prompt, emitPrompt = false)
      val firstLine = text.linesIterator
        .map(_.trim)
        .filterNot(_.startsWith("```"))
        .find(_.nonEmpty)
        .getOrElse("")
      if firstLine.isBlank then fallback else firstLine
    catch case NonFatal(_) => fallback
