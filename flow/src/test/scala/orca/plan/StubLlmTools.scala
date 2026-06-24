package orca.plan

import orca.llm.{
  AgentInput,
  Announce,
  AutonomousLlmCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveLlmCall,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId,
  ToolSet
}

/** Test double whose `resultAs[O].autonomous.run` returns a pre-built `value`
  * (cast to `O`) paired with a fixed session id. Other call shapes throw so
  * accidental use surfaces immediately. One stub serves every autonomous
  * planning operation — pass a `Plan`, `AssessedPlan`, or `BugTriage`.
  */
private[plan] class CannedResultLlm[T](value: T)
    extends LlmTool[BackendTag.ClaudeCode.type]:
  val name: String = "stub"

  /** Records the most recent `withTools` tier so tests can assert which
    * capability a helper selected (e.g. planners use `NetworkOnly`).
    */
  var lastToolSet: Option[ToolSet] = None
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withTools(tools: ToolSet): LlmTool[BackendTag.ClaudeCode.type] =
    lastToolSet = Some(tools)
    this

  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] =
    new LlmCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
          def run[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: LlmConfig,
              emitPrompt: Boolean
          )(using orca.InStage): (SessionId[BackendTag.ClaudeCode.type], O) =
            (
              SessionId[BackendTag.ClaudeCode.type]("stub-sid"),
              value.asInstanceOf[O]
            )
      def interactive: InteractiveLlmCall[BackendTag.ClaudeCode.type, O] = ???
