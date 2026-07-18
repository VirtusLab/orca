package orca.agents

import orca.util.PromptResource

/** Builds the literal prompt strings Orca sends to the LLM for each invocation
  * mode, from the serialized user input, the generated JSON Schema for the
  * expected output, and the active `AgentConfig`. Swap the default
  * ([[DefaultPrompts]]) by passing `prompts = ...` to `flow(...)`.
  */
trait Prompts:
  /** Prompt for a non-interactive call: the model produces the structured
    * response in a single agentic turn. `mode` is how the wire expects the
    * payload (a StructuredOutput tool call vs raw reply text), so the delivery
    * instruction names the actual mechanism.
    */
  def autonomous(
      input: String,
      outputSchema: String,
      config: AgentConfig,
      mode: StructuredOutputMode
  ): String

  /** Prompt for an interactive call: the model converses with the user on
    * intermediate turns, then produces a single JSON value matching the output
    * schema as its final turn. The runtime validates it against the schema via
    * `--json-schema` (or equivalent); no in-band completion marker is required.
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
  * backend: claude enforces it CLI-side via `--json-schema`; text-only backends
  * rely on `ResponseParser` + the retry loop. Autonomous delivery rules follow
  * the backend's [[StructuredOutputMode]]: `Tool` backends call the
  * StructuredOutput tool, `RawText` backends keep the raw-JSON contract.
  */
object DefaultPrompts extends Prompts:

  private val RawJsonRules: String =
    PromptResource.load("/orca/agents/prompts/raw-json-rules.md")

  private val ToolCallRules: String =
    PromptResource.load("/orca/agents/prompts/tool-call-rules.md")

  // Substitute the shared rules fragments once at init so each call only pays
  // for the dynamic `{{...}}` replacements. One autonomous template per
  // StructuredOutputMode, selected via an exhaustive match in [[autonomous]] so
  // a new mode is a compile error here rather than a lookup failure at runtime.
  private val AutonomousBase: String =
    PromptResource.load("/orca/agents/prompts/autonomous.md")

  private val AutonomousRawTextTemplate: String =
    AutonomousBase.replace("{{resultRules}}", RawJsonRules)

  private val AutonomousToolTemplate: String =
    AutonomousBase.replace("{{resultRules}}", ToolCallRules)

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
    val template = mode match
      case StructuredOutputMode.RawText => AutonomousRawTextTemplate
      case StructuredOutputMode.Tool    => AutonomousToolTemplate
    PromptResource.render(
      template,
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
