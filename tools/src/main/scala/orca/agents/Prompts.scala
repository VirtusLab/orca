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

  /** Builds a prompt asking the model to retry after a parse failure. Assumes
    * it is sent as a follow-up turn on the same session — the original output
    * schema is expected to still be visible in prior context. `mode` picks the
    * delivery instruction, as in [[autonomous]]: a retry that asked a Tool-mode
    * backend for raw JSON would talk it out of the tool call the wire expects.
    */
  def retry(
      failedResponse: String,
      parseError: String,
      mode: StructuredOutputMode
  ): String

/** Default [[Prompts]] implementation. Templates live as `.md` resources under
  * `src/main/resources/orca/agents/prompts/` and are loaded once at object
  * init.
  *
  * The autonomous and retry templates come in one variant per
  * [[StructuredOutputMode]], selected via an exhaustive match so a new mode
  * shows up here as an exhaustivity warning rather than a lookup failure at
  * runtime. `RawText` backends carry the whole contract in the prompt — the
  * schema plus the raw-JSON rules — because nothing else holds them to it on
  * every turn. `Tool` backends get neither: the CLI-injected StructuredOutput
  * tool already carries the schema, so the prompt only names the tool.
  */
object DefaultPrompts extends Prompts:

  private val RawJsonRules: String =
    PromptResource.load("/orca/agents/prompts/raw-json-rules.md")

  // Substitute the shared rules fragment once at init so each call only pays
  // for the dynamic `{{...}}` replacements.
  private val AutonomousRawTextTemplate: String =
    PromptResource
      .load("/orca/agents/prompts/autonomous-raw-text.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  private val AutonomousToolTemplate: String =
    PromptResource.load("/orca/agents/prompts/autonomous-tool.md")

  private val InteractiveTemplate: String =
    PromptResource
      .load("/orca/agents/prompts/interactive.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  private val RetryRawTextTemplate: String =
    PromptResource
      .load("/orca/agents/prompts/retry-raw-text.md")
      .replace("{{rawJsonRules}}", RawJsonRules)

  private val RetryToolTemplate: String =
    PromptResource.load("/orca/agents/prompts/retry-tool.md")

  def autonomous(
      input: String,
      outputSchema: String,
      config: AgentConfig,
      mode: StructuredOutputMode
  ): String = mode match
    case StructuredOutputMode.RawText =>
      PromptResource.render(
        AutonomousRawTextTemplate,
        "input" -> input,
        "outputSchema" -> outputSchema
      )
    case StructuredOutputMode.Tool =>
      PromptResource.render(AutonomousToolTemplate, "input" -> input)

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

  def retry(
      failedResponse: String,
      parseError: String,
      mode: StructuredOutputMode
  ): String =
    val template = mode match
      case StructuredOutputMode.RawText => RetryRawTextTemplate
      case StructuredOutputMode.Tool    => RetryToolTemplate
    PromptResource.render(
      template,
      "failedResponse" -> failedResponse,
      "parseError" -> parseError
    )
