package orca

import orca.llm.{BackendTag, LlmTool}

import scala.util.control.NonFatal

extension [B <: BackendTag](llm: LlmTool[B])
  /** Best-effort one-line reply from the cheap model. Runs `prompt` on
    * `llm.cheap.withReadOnly` (no prompt echo), and returns the first line
    * trimmed — or `fallback` if that line is blank or the call fails for any
    * non-fatal reason.
    *
    * Never throws: incidental cheap-model calls (branch naming, default commit
    * messages) must never break a flow. Requires `InStage` because it is a
    * gated LLM call.
    */
  def cheapOneShot(prompt: String, fallback: => String)(using InStage): String =
    try
      val (_, text) =
        llm.cheap.withReadOnly.autonomous.run(prompt, emitPrompt = false)
      val firstLine = text.linesIterator.nextOption().getOrElse("").trim
      if firstLine.isBlank then fallback else firstLine
    catch case NonFatal(_) => fallback
