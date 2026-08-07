package orca.review

import orca.agents.{
  AgentConfig,
  AutoApprove,
  BackendTag,
  DefaultAgentCall,
  DefaultPrompts,
  Enforcement,
  EnforcementCell,
  TurnDispatch,
  SessionId,
  StructuredOutputMode,
  ToolSet,
  WireSessionId
}
import orca.backend.{
  AgentBackend,
  AgentResult,
  Conversation,
  IdScheme,
  Interaction,
  SessionSupport
}
import orca.events.{OrcaEvent, OrcaListener, Usage}
import orca.plan.Title
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

/** Reproduces the review-fix turn's backend call with a canned `FixOutcome`
  * payload — no subprocess, just [[DefaultAgentCall]] wired to a fake
  * [[AgentBackend]] — to pin the actual leak: without `FixOutcome`'s `Announce`
  * instance, `emitStructuredResult` resolves the catch-all and the raw JSON
  * renders under the same `●` glyph as prose (ADR 0008), on top of the fix
  * loop's own "Fixed N, ignored N" line.
  */
private class CannedBackend(output: String)
    extends AgentBackend[BackendTag.Pi.type]:
  val workDir: os.Path = os.pwd
  val sessions: SessionSupport[BackendTag.Pi.type] =
    SessionSupport.ephemeral(IdScheme.ClientClaimed)
  val tag: BackendTag.Pi.type = BackendTag.Pi
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell =
    EnforcementCell(Enforcement.Hard, "test double: nothing to report")
  def structuredOutputMode: StructuredOutputMode = StructuredOutputMode.RawText
  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      config: AgentConfig,
      events: OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.Pi.type] =
    AgentResult(
      WireSessionId[BackendTag.Pi.type]("wire-test"),
      output,
      Usage.empty
    )
  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.Pi.type],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using ox.Ox): Conversation[BackendTag.Pi.type] =
    throw new UnsupportedOperationException("test stub")

class FixOutcomeAnnounceTest extends munit.FunSuite:
  private given orca.InStage = orca.InStage.unsafe

  // The autonomous path never calls `drive` — only stands in so
  // `DefaultAgentCall`'s constructor is satisfied.
  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    )(using ox.Ox): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  test(
    "the fix turn's StructuredResult carries Some(\"\"), not the raw-JSON fallback"
  ):
    val backend = new CannedBackend(
      """{"fixed":["Fix the thing"],"ignored":[]}"""
    )
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val call = new DefaultAgentCall[BackendTag.Pi.type, FixOutcome](
      backend = backend,
      effectiveConfig = _.getOrElse(AgentConfig()),
      prompts = DefaultPrompts,
      events = (e: OrcaEvent) => { val _ = seen.updateAndGet(e :: _) },
      interaction = stubInteraction,
      agentName = "coder"
    )
    supervised:
      val outcome = call.autonomous.run(
        FixRequest("fix it", Nil)
      )
      assertEquals(outcome, FixOutcome(List(Title("Fix the thing")), Nil))
      val summaries = seen.get().collect {
        case OrcaEvent.StructuredResult(_, summary) => summary
      }
      assertEquals(summaries, List(Some("")))
