package orca.testkit

import orca.agents.{
  Agent,
  AgentCall,
  AgentConfig,
  Announce,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  SessionId,
  ToolSet
}

import java.util.concurrent.ConcurrentLinkedQueue

/** Agent stub whose text turns record the prompt they were given and return a
  * fixed reply — the cheap model the runtime drafts commit messages with.
  * `cheap` is this same stub, since that is the tier `cheapOneShot` runs.
  *
  * Any stage that changes a file drafts a message, so a `TestFlowControl`
  * driving such a stage needs one of these as its lead.
  */
class TextReplyingAgent(
    reply: String,
    prompts: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String]()
) extends Agent[BackendTag.ClaudeCode.type]:
  val name: String = "stubbed"
  override def cheap: Agent[BackendTag.ClaudeCode.type] = this
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
    new AutonomousTextCall[BackendTag.ClaudeCode.type]:
      private[orca] def runWithSession(
          prompt: String,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: Option[AgentConfig],
          emitPrompt: Boolean
      )(using orca.InStage): String =
        prompts.add(prompt): Unit
        reply
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] = ???
