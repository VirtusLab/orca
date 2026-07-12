package orca.plan

import orca.agents.{
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveAgentCall,
  JsonData,
  AgentCall,
  AgentConfig,
  Agent,
  SessionId,
  ToolSet
}

/** Test double whose `resultAs[O].autonomous.run` returns a pre-built `value`
  * (cast to `O`) paired with a fixed session id. Other call shapes throw so
  * accidental use surfaces immediately. One stub serves every autonomous
  * planning operation — pass a `Plan`, `AssessedPlan`, or `BugTriage`.
  */
private[plan] class CannedResultAgent[T](value: T)
    extends Agent[BackendTag.ClaudeCode.type]:
  val name: String = "stub"

  /** Records the most recent `withTools` tier so tests can assert which
    * capability a helper selected (e.g. planners use `NetworkOnly`).
    */
  var lastToolSet: Option[ToolSet] = None

  /** Records the session the structured run was called with, so tests can
    * assert the returned [[orca.agents.Chat]] continues that conversation.
    */
  var lastSession: Option[SessionId[BackendTag.ClaudeCode.type]] = None
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] =
    lastToolSet = Some(tools)
    this

  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          private[orca] def runWithSession[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): (SessionId[BackendTag.ClaudeCode.type], O) =
            lastSession = Some(session)
            (session, value.asInstanceOf[O])
      def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] = ???
