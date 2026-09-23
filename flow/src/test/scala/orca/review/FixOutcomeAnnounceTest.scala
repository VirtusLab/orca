package orca.review

import orca.testkit.ScriptedBackend
import orca.agents.{AgentConfig, BackendTag, DefaultAgentCall, DefaultPrompts}
import orca.backend.{
  AgentResult,
  Interaction,
  TurnRequest,
  ObservedConversation
}
import orca.events.{OrcaEvent, OrcaListener}
import orca.plan.Title
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

/** Reproduces the review-fix turn's backend call with a canned `FixOutcome`
  * payload — no subprocess, just [[DefaultAgentCall]] wired to a fake
  * [[AgentBackend]] — to pin the actual leak: without `FixOutcome`'s `Announce`
  * instance, `emitStructuredResult` resolves the catch-all and the raw JSON
  * renders under the same `●` glyph as prose (ADR 0008), on top of the fix
  * loop's own "Fixed N, declined N" line.
  */
private class CannedBackend(output: String)
    extends ScriptedBackend(BackendTag.Pi):
  protected def reply(
      turn: TurnRequest[BackendTag.Pi.type]
  ): AgentResult[BackendTag.Pi.type] = ScriptedBackend.result(output)

class FixOutcomeAnnounceTest extends munit.FunSuite:
  private given orca.InStage = orca.InStage.unsafe

  // The autonomous path never calls `drive` — only stands in so
  // `DefaultAgentCall`'s constructor is satisfied.
  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: ObservedConversation[B]
    ): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  test(
    "the fix turn's StructuredResult carries Some(\"\"), not the raw-JSON fallback"
  ):
    val backend = new CannedBackend(
      """{"fixed":["Fix the thing"],"declined":[]}"""
    )
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val call = new DefaultAgentCall[BackendTag.Pi.type, FixOutcome](
      backend = backend,
      config = AgentConfig(),
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
