package orca.llm

import orca.util.PromptResource

/** Builds the literal prompt strings Orca sends to the LLM for each invocation
  * mode. Each method takes the serialized user input, the generated JSON Schema
  * for the expected output, and the active `LlmConfig`, and returns the final
  * prompt text. Swap the default ([[DefaultPrompts]]) by passing `prompts =
  * ...` to `flow(...)` when you want to customise phrasing, add guardrails, or
  * use a different structured-output convention.
  */
trait Prompts:
  /** Prompt for a non-interactive call: the model is expected to emit the
    * structured JSON response directly, with no user turn in between.
    */
  def autonomous(input: String, outputSchema: String, config: LlmConfig): String

  /** Prompt for an interactive call: the model converses with the user on
    * intermediate turns, then produces a single JSON value matching the output
    * schema as its final turn. The runtime validates the final value against
    * the schema via `--json-schema` (or equivalent backend mechanism); no
    * in-band completion marker is required.
    */
  def interactive(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String

  /** Builds a prompt asking the model to retry after a JSON parse failure.
    * Assumes it is sent as a follow-up turn on the same session — the original
    * output schema is expected to still be visible in prior context.
    */
  def retry(failedResponse: String, parseError: String): String

/** Default [[Prompts]] implementation. Templates live as `.md` resources under
  * `src/main/resources/orca/llm/prompts/` and are loaded once at object init.
  *
  * Autonomous calls ship the JSON Schema inline in the prompt — they route
  * through `claude -p --output-format json` with no structured validation on
  * the CLI side. Interactive calls rely on `--json-schema` for enforcement and
  * let the agent reply in natural conversation until it has the final
  * structured value; the schema is still summarised in the prompt so the model
  * knows the target shape, but no magic marker is required.
  */
object DefaultPrompts extends Prompts:

  private val RawJsonRules: String =
    PromptResource.load("/orca/llm/prompts/raw-json-rules.md")

  // Substitute the shared rules fragment once at init so each call only pays
  // for the dynamic `{{input}}` / `{{outputSchema}}` / `{{failedResponse}}` /
  // `{{parseError}}` replacements.
  private val AutonomousTemplate: String =
    PromptResource
      .load("/orca/llm/prompts/autonomous.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  private val InteractiveTemplate: String =
    PromptResource
      .load("/orca/llm/prompts/interactive.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  private val RetryTemplate: String =
    PromptResource
      .load("/orca/llm/prompts/retry.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  def autonomous(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String =
    PromptResource.render(
      AutonomousTemplate,
      "input" -> input,
      "outputSchema" -> outputSchema
    )

  def interactive(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String =
    PromptResource.render(
      InteractiveTemplate,
      "input" -> input,
      "outputSchema" -> outputSchema
    )

  def retry(failedResponse: String, parseError: String): String =
    PromptResource.render(
      RetryTemplate,
      "failedResponse" -> failedResponse,
      "parseError" -> parseError
    )
