package orca

/** Builds the literal prompt strings Orca sends to the LLM for each invocation
  * mode. Each method takes the serialized user input, the generated JSON Schema
  * for the expected output, and the active `LlmConfig`, and returns the final
  * prompt text. Swap the default ([[orca.io.DefaultPrompts]]) by passing
  * `prompts = ...` to `flow(...)` when you want to customise phrasing, add
  * guardrails, or use a different structured-output convention.
  *
  * The schema is a parameter (rather than something the backend always threads
  * in via `--json-schema`/`--output-schema`) because not every code path
  * enforces structured output at the CLI: claude headless (`-p --output-format
  * json`) doesn't, codex `exec resume` rejects the flag, and codex headless
  * calls don't carry one. Embedding the schema in the prompt is the only signal
  * those paths get; for paths where the backend also enforces it, it's
  * belt-and-suspenders.
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
