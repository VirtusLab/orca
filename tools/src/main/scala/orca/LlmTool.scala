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
  // TODO: comment that this is a quick version of result[String].(...)
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String
  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]

trait ClaudeTool extends LlmTool[Backend.ClaudeCode.type]:
  // TODO: add a comment that this overrides the default llm config
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[Backend.Codex.type]:
  def mini: CodexTool

trait LlmCall[B <: Backend, O]:
  // TODO: I'm wondering what's the most intuitive syntax here is. Specifying the result before the prompt might not be best? Some alternatives:
  // claude.result[X].prompt("...") -> current
  // claude.prompt("...").result[X] -> but might be misleading that .prompt(...) doesn't send the request
  // claude.prompt("...", result[X]) -> result[] might be hard to discover
  // any ideas?
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
