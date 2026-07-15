package orca.agents

import orca.util.PromptResource

/** Builds the literal prompt strings Orca sends to the LLM for each invocation
  * mode. Each method takes the serialized user input, the generated JSON Schema
  * for the expected output, and the active `AgentConfig`, and returns the final
  * prompt text. Swap the default ([[DefaultPrompts]]) by passing `prompts =
  * ...` to `flow(...)` when you want to customise phrasing, add guardrails, or
  * use a different structured-output convention.
  */
trait Prompts:
  /** Prompt for a non-interactive call: the model produces the structured
    * response in a single agentic turn, with no user turn in between. `mode` is
    * the backend's declared [[StructuredOutputMode]] — how the wire expects the
    * payload (a StructuredOutput tool call vs raw reply text) — so the delivery
    * instruction names the actual mechanism instead of contradicting it.
    */
  def autonomous(
      input: String,
      outputSchema: String,
      config: AgentConfig,
      mode: StructuredOutputMode
  ): String

  /** Prompt for an interactive call: the model converses with the user on
    * intermediate turns, then produces a single JSON value matching the output
    * schema as its final turn. The runtime validates the final value against
    * the schema via `--json-schema` (or equivalent backend mechanism); no
    * in-band completion marker is required.
    */
  def interactive(
      input: String,
      outputSchema: String,
      config: AgentConfig
  ): String

  /** Builds a prompt asking the model to retry after a JSON parse failure.
    * Assumes it is sent as a follow-up turn on the same session — the original
    * output schema is expected to still be visible in prior context.
    */
  def retry(failedResponse: String, parseError: String): String

/** Default [[Prompts]] implementation. Templates live as `.md` resources under
  * `src/main/resources/orca/agents/prompts/` and are loaded once at object
  * init.
  *
  * Both call modes ship the JSON Schema inline in the prompt AND hand it to the
  * backend (`outputSchema = Some(...)`): claude passes `--json-schema`, which
  * enforces the schema CLI-side via an injected StructuredOutput tool whose
  * parameters are the schema's top-level properties; text-only backends rely on
  * `ResponseParser` + the retry loop for structural validation. The autonomous
  * delivery rules follow the backend's declared [[StructuredOutputMode]]:
  * `Tool` backends are told to call the StructuredOutput tool, `RawText`
  * backends keep the raw-JSON contract.
  */
object DefaultPrompts extends Prompts:

  private val RawJsonRules: String =
    PromptResource.load("/orca/agents/prompts/raw-json-rules.md")

  private val ToolCallRules: String =
    PromptResource.load("/orca/agents/prompts/tool-call-rules.md")

  // Substitute the shared rules fragments once at init so each call only pays
  // for the dynamic `{{input}}` / `{{outputSchema}}` / `{{failedResponse}}` /
  // `{{parseError}}` replacements. The autonomous template exists in one
  // variant per StructuredOutputMode: same task framing, delivery rules
  // matching what the backend's wire actually expects.
  private val AutonomousTemplates: Map[StructuredOutputMode, String] =
    val base = PromptResource.load("/orca/agents/prompts/autonomous.md")
    Map(
      StructuredOutputMode.RawText ->
        base.replace("{{resultRules}}", RawJsonRules),
      StructuredOutputMode.Tool ->
        base.replace("{{resultRules}}", ToolCallRules)
    )

  private val InteractiveTemplate: String =
    PromptResource
      .load("/orca/agents/prompts/interactive.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  private val RetryTemplate: String =
    PromptResource
      .load("/orca/agents/prompts/retry.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  def autonomous(
      input: String,
      outputSchema: String,
      config: AgentConfig,
      mode: StructuredOutputMode
  ): String =
    PromptResource.render(
      AutonomousTemplates(mode),
      "input" -> input,
      "outputSchema" -> outputSchema
    )

  def interactive(
      input: String,
      outputSchema: String,
      config: AgentConfig
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
