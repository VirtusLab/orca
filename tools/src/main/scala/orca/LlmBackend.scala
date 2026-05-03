package orca

case class LlmResult[B <: Backend](
    sessionId: SessionId[B],
    output: String,
    usage: Usage,
    /** Model the backend reports it actually served the call with — usually
      * present for headless calls (the CLI returns it), absent for some
      * conversation paths. Used as the bucket key in `CostTracker` so spend is
      * attributed to the concrete model rather than just the tool name.
      */
    model: Option[String] = None
)

trait LlmBackend[B <: Backend]:
  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[B]

  def continueHeadless(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[B]

  /** Launch an interactive session and return a live [[Conversation]] the
    * caller hands to an [[Interaction.drive]] for rendering and user steering.
    * The backend owns the subprocess and NDJSON parsing; the channel owns UX.
    *
    * `prompt` is the full wire-level message sent to the agent (with template
    * scaffolding, schema, rules). `displayPrompt` is what the channel shows the
    * user to anchor the session — typically just the user's raw input. Keeping
    * them separate prevents the renderer from dumping the whole templated
    * system prompt into the terminal.
    *
    * `outputSchema` is the JSON Schema the agent's final reply must conform to,
    * or `None` for free-form text. Backends that support structured-output
    * validation (claude's `--json-schema`) enforce it; those that don't can
    * ignore the parameter and let the caller validate post-hoc.
    */
  def runInteractive(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[B]

  def continueInteractive(
      sessionId: SessionId[B],
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[B]
