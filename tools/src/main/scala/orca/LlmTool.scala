package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

/** An LLM adapter usable from flow scripts — the handle you call from
  * `orca:` blocks (`claude`, `codex`, etc.) to run prompts, start or continue
  * sessions, and hand off interactive control. Parameterized by the concrete
  * `Backend` so session ids and results carry the backend identity at the
  * type level.
  */
trait LlmTool[B <: Backend]:
  def name: String
  /** Fix the output type of the call, then chain `.prompt(...)` /
    * `.interactive(...)` / `.continueSession(...)` to actually invoke the
    * model. `O` must carry a tapir `Schema` (for prompt generation) and a
    * jsoniter-scala `ConfiguredJsonValueCodec` (for parsing the response) —
    * `derives JsonData` on `O` provides both.
    */
  def result[O: Schema: ConfiguredJsonValueCodec]: LlmCall[B, O]
  /** One-shot headless call that takes a string and returns a string —
    * equivalent to `result[String].prompt(prompt, config)` without the need
    * for a schema or codec. Use when the response is free-form text rather
    * than a structured value.
    */
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String
  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]

trait ClaudeTool extends LlmTool[Backend.ClaudeCode.type]:
  /** Returns a variant of this tool that pins the Claude model for
    * subsequent calls, overriding `LlmConfig.model`. Typical usage:
    * `claude.haiku.ask("summarize this")` for a cheap fast call.
    */
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[Backend.Codex.type]:
  def mini: CodexTool

/** One configured LLM call of a given output type. Obtained via
  * `tool.result[O]`; the returned value supports every invocation variant
  * (headless `prompt`, session-based `startSession` / `continueSession`, and
  * `interactive` / `continueInteractive`) so callers can switch execution
  * mode without restating `O`.
  */
trait LlmCall[B <: Backend, O]:
  /** Run the call headlessly: a single turn, no session retained. */
  def prompt[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): O
  // TODO: since we have "interactive", maybe this should be "autonomous" for symmetry?
  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O)
  def continueSession[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O
  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O)
  def continueInteractive[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O
