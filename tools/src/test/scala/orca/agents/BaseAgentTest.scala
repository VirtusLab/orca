package orca.agents

import orca.testkit.StubEnforcementCell
import orca.backend.{
  Conversation,
  ConversationEvent,
  Conversations,
  Interaction,
  AgentBackend,
  AgentResult,
  IdScheme,
  SessionSupport
}
import orca.events.{OrcaEvent, OrcaListener, TurnDebit, Usage}
import orca.testkit.Usages.usage
import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

/** Unsupported `resultAs[O]` output shape: OpenAI's strict structured-output
  * mode can't express a Map's unbounded key set. See the "Map field throws...
  * at construction" test below.
  */
case class MapCarrier(m: Map[String, Int]) derives JsonData

/** A structured-call output shape shaped like a real flow result, used by the
  * raw-payload-suppression tests below.
  */
private case class FixOutcome(fixed: List[String], ignored: List[String])
    derives JsonData

private object BaseAgentTest:
  /** The `EnforcementNotice` line for an ungated read-only `turn`, spelled out
    * once so the tests below assert the sentence a user sees, not a fragment.
    */
  def noEditNotice(turn: String): String =
    s"Pi cannot stop a $turn turn from editing files or running commands " +
      "that change state — only the turn's own prompt asks it not to"

class BaseAgentTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  test("close() delegates to the backend"):
    val backend = new RecordingCloseBackend
    val tool = new StubTool(backend)
    tool.close()
    assertEquals(backend.closeCount, 1)

  // A closed agent must fail loud rather than let a leaked handle
  // silently emit to a closed run's dispatcher.
  test("run after close() throws OrcaFlowException"):
    val tool = new StubTool(new RecordingCloseBackend)
    tool.close()
    val thrown = intercept[orca.OrcaFlowException]:
      tool.run("prompt")
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  // An unsupported output shape (a Map[String, _] field, rejected by
  // JsonSchemaGen) must fail at `resultAs[O]` construction, before any stage
  // runs — not remotely, after `.run()` spawns a backend process.
  test("resultAs[O] with a Map field throws OrcaFlowException at construction"):
    val tool = new StubTool(StubBackend)
    val thrown = intercept[orca.OrcaFlowException]:
      tool.resultAs[MapCarrier]
    assert(
      thrown.getMessage.contains("List of key/value case classes"),
      s"expected actionable Map-field message, got: ${thrown.getMessage}"
    )

  test("resultAs after close() throws OrcaFlowException"):
    val tool = new StubTool(new RecordingCloseBackend)
    tool.close()
    val thrown = intercept[orca.OrcaFlowException]:
      tool.resultAs[String]
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  // The closed latch lives on the shared backend, so it survives the two ways a
  // leaked handle re-derives a "fresh" object after close: copyTool builders (a
  // new Agent over the same backend) and a resultAs gateway built before close
  // and invoked after.
  test("a copyTool-derived handle after close() throws OrcaFlowException"):
    val tool = new StubTool(new RecordingCloseBackend)
    tool.close()
    val derived = tool.withName("derived")
    val thrown = intercept[orca.OrcaFlowException]:
      derived.run("prompt")
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  test("a resultAs gateway obtained before close() throws when run after it"):
    val tool = new StubTool(new RecordingCloseBackend)
    val gateway = tool.resultAs[String]
    tool.close()
    val thrown = intercept[orca.OrcaFlowException]:
      gateway.autonomous.run("prompt")
    assertEquals(
      thrown.getMessage,
      AgentBackend.ClosedMessage
    )

  // The runtime's internal cheap turns (branch naming, commit messages) consume
  // their reply and re-surface it as the caller's own Step event; streaming the
  // turn too would print the same text twice.
  test(
    "cheapOneShot suppresses the turn's display events; TokensUsed still flows"
  ):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(new NoisyBackend, listener = listener)
    val reply = tool.cheapOneShot(
      purpose = "branch name",
      prompt = "name this branch",
      fallback = "fb"
    )
    assertEquals(reply, "short-label")
    val events = seen.get()
    assert(
      !events.exists(_.isInstanceOf[OrcaEvent.AssistantMessage]),
      s"assistant prose must be suppressed on a quiet turn: $events"
    )
    assert(
      !events.exists(_.isInstanceOf[OrcaEvent.ToolUse]),
      s"tool-use lines must be suppressed on a quiet turn: $events"
    )
    assert(
      !events.exists(_.isInstanceOf[OrcaEvent.UserPrompt]),
      s"the prompt echo must be suppressed on a quiet turn: $events"
    )
    assert(
      events.exists(_.isInstanceOf[OrcaEvent.TokensUsed]),
      s"cost accounting must still flow on a quiet turn: $events"
    )

  // A one-shot that fails is best-effort by design, but the fallback must not
  // be silent: the user needs to know which incidental agent failed, why, and
  // that orca carried on with the default.
  test("a failing cheapOneShot returns its fallback and reports the purpose"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(
      new FailingBackend(new RuntimeException("Prompt is too long")),
      listener = listener
    )
    val reply = tool.cheapOneShot(
      purpose = "commit message",
      prompt = "summarise this diff",
      fallback = "stage: build"
    )
    assertEquals(reply, "stage: build")
    val steps = seen.get().collect { case OrcaEvent.Step(m) => m }
    assertEquals(
      steps,
      List(
        "commit message agent failed (Prompt is too long) — " +
          "using the default commit message instead"
      )
    )

  // Cheap models routinely narrate before answering and fence the answer; the
  // narration line must not become the commit message / branch label.
  test("cheapOneShot prefers a fenced answer over the narration above it"):
    val tool = new StubTool(
      new ScriptedDrainBackend(
        "Looking at this diff, the main changes are:\n\n```\nShow branch in menu\n```\n"
      ),
      prompts = DefaultPrompts
    )
    val reply = tool.cheapOneShot(
      purpose = "commit message",
      prompt = "summarise this diff",
      fallback = "stage: build"
    )
    assertEquals(reply, "Show branch in menu")

  // A caller that asks for a no-edit tier and gets prose has no other signal
  // that the gate isn't mechanical — and a fan-out would repeat the notice per
  // turn, which is why it is deduplicated rather than emitted per run call.
  test("a read-only tier the backend doesn't gate is reported once"):
    val steps = new StepRecorder
    val tool = new StubTool(
      new UngatedBackend("first", "second"),
      toolConfig = AgentConfig(tools = ToolSet.ReadOnly),
      listener = steps.listener,
      prompts = DefaultPrompts
    )
    val _ = tool.run("one")
    val _ = tool.run("two")
    assertEquals(steps.seen, List(BaseAgentTest.noEditNotice("ReadOnly")))

  // Reviewers — the turns this notice exists for — reach the backend through
  // `resultAs`, which dispatches on its own path rather than through the text
  // one, so it has to raise the notice itself.
  test("a read-only structured turn reports the shortfall too"):
    val steps = new StepRecorder
    val tool = new StubTool(
      new UngatedBackend("""{"fixed":[],"ignored":[]}"""),
      toolConfig = AgentConfig(tools = ToolSet.NetworkOnly),
      listener = steps.listener,
      prompts = DefaultPrompts
    )
    val _ = tool.resultAs[FixOutcome].autonomous.run("fix compile errors")
    assertEquals(steps.seen, List(BaseAgentTest.noEditNotice("NetworkOnly")))

  // The gate lives on `AgentBackend.runAutonomous` itself, so a turn entry
  // point that never goes through `BaseAgent` or `DefaultAgentCall` still
  // announces — a new door cannot forget it.
  test("a direct runAutonomous call announces the shortfall"):
    val steps = new StepRecorder
    val _ = new UngatedBackend("reply").runAutonomous(
      "one",
      SessionId.fresh[BackendTag.Pi.type],
      AgentConfig(tools = ToolSet.ReadOnly),
      steps.listener
    )
    assertEquals(steps.seen, List(BaseAgentTest.noEditNotice("ReadOnly")))

  // The first attempt commits the session, so the corrective re-prompt runs as
  // a resumed turn — which on this backend (codex's shape) is where the gate
  // disappears. Classified once per call instead of once per attempt, the
  // retry's weaker guarantee would go unreported: the first attempt is `Hard`,
  // so the single Step below is entirely the second attempt's, and says so.
  test("a corrective retry reports the resumed turn's weaker guarantee"):
    val steps = new StepRecorder
    val tool = new StubTool(
      new WeakerOnResumeBackend(
        "not json at all",
        """{"fixed":[],"ignored":[]}"""
      ),
      toolConfig = AgentConfig(
        tools = ToolSet.ReadOnly,
        retrySchedule = Schedule.exponentialBackoff(1.milli).maxRetries(1)
      ),
      listener = steps.listener,
      prompts = DefaultPrompts
    )
    val _ = tool.resultAs[FixOutcome].autonomous.run("fix compile errors")
    assertEquals(
      steps.seen,
      List(BaseAgentTest.noEditNotice("resumed ReadOnly"))
    )

  // The turn is named "resumed" whenever resuming is what weakened the answer,
  // not only where the fresh turn was a hard gate: this backend approximates the
  // `Only` list on the spawn and encodes nothing at all on the resume, and the
  // second sentence has to say which turn it is about.
  test("a retry whose approximation is dropped names the resumed turn"):
    val steps = new StepRecorder
    val tool = new StubTool(
      new WeakerOnResumeBackend(
        "not json at all",
        """{"fixed":[],"ignored":[]}"""
      ),
      toolConfig = AgentConfig(
        autoApprove = AutoApprove.Only(Set("read")),
        retrySchedule = Schedule.exponentialBackoff(1.milli).maxRetries(1)
      ),
      listener = steps.listener,
      prompts = DefaultPrompts
    )
    val _ = tool.resultAs[FixOutcome].autonomous.run("fix compile errors")
    assertEquals(
      steps.seen,
      List(
        "Pi cannot hold a Full turn to the tools it was asked to auto-approve" +
          " — the sandbox it runs in is wider than that",
        "Pi cannot hold a resumed Full turn to the tools it was asked to" +
          " auto-approve — nothing orca puts on the wire says so"
      )
    )

  // The other axis: `Only` asks the backend to auto-approve just those tools,
  // and a backend that encodes no such list runs the agent wider than asked
  // with nothing else saying so.
  test("a Full turn whose Only list isn't encoded is reported"):
    val steps = new StepRecorder
    val tool = new StubTool(
      new UngatedBackend("only"),
      toolConfig = AgentConfig(autoApprove = AutoApprove.Only(Set("read"))),
      listener = steps.listener,
      prompts = DefaultPrompts
    )
    val _ = tool.run("one")
    assertEquals(
      steps.seen,
      List(
        "Pi cannot hold a Full turn to the tools it was asked to auto-approve" +
          " — only the turn's own prompt asks it not to"
      )
    )

  // The notice answers "I asked for a restriction and didn't get one". A `Full`
  // turn approving everything asked for none, so the same weak declaration must
  // stay silent.
  test("a Full turn approving everything reports no shortfall"):
    val steps = new StepRecorder
    val tool = new StubTool(
      new UngatedBackend("only"),
      listener = steps.listener,
      prompts = DefaultPrompts
    )
    val _ = tool.run("one")
    assertEquals(steps.seen, Nil)

  // The log lives on the backend, so two backends of the same kind — a flow
  // that wires its own second one — each say their piece. Documented on
  // `AgentBackend`; pinned here because it is a consequence of where the state
  // sits rather than of anything the notice does.
  test("a second backend of the same kind gives its own notice"):
    val steps = new StepRecorder
    def readOnlyTool = new StubTool(
      new UngatedBackend("reply"),
      toolConfig = AgentConfig(tools = ToolSet.ReadOnly),
      listener = steps.listener,
      prompts = DefaultPrompts
    )
    val _ = readOnlyTool.run("one")
    val _ = readOnlyTool.run("two")
    assertEquals(
      steps.seen,
      List.fill(2)(BaseAgentTest.noEditNotice("ReadOnly"))
    )

  // A turn that failed after the model ran still spent tokens; the success path
  // is the only other TokensUsed emitter, so without this the failed turn is
  // invisible in the run's cost summary.
  test("a turn failing with reported usage still emits TokensUsed"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val spent = usage(120L, 8L, Some(BigDecimal("0.0031")))
    val tool = new StubTool(
      new FailingBackend(
        new orca.AgentTurnFailed(
          "claude session failed",
          TurnDebit.Observed(spent, None)
        )
      ),
      listener = listener
    )
    val _ = intercept[orca.AgentTurnFailed](tool.run("prompt"))
    assertEquals(
      seen.get().collect { case t: OrcaEvent.TokensUsed => t.usage },
      List(spent)
    )

  test("a retried structured turn reports the next attempt index"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(
      // The first reply doesn't parse as FixOutcome, so the call re-prompts.
      new ScriptedDrainBackend(
        "not json at all",
        """{"fixed":[],"ignored":[]}"""
      ),
      toolConfig = AgentConfig(retrySchedule =
        Schedule.exponentialBackoff(1.milli).maxRetries(1)
      ),
      listener = listener,
      prompts = DefaultPrompts
    )
    val _ = tool.resultAs[FixOutcome].autonomous.run("fix compile errors")
    assertEquals(
      seen.get().reverse.collect { case t: OrcaEvent.TokensUsed => t.attempt },
      List(1, 2)
    )

  // An attempt that dies before the model runs is retried but records no turn,
  // so it must not push the turn that follows to `2` and make a first try look
  // like retry overhead.
  test("an attempt that failed before the model ran doesn't advance the index"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(
      new FailFirstBackend(
        // Not AgentTurnFailed, so the retry policy retries it — the shape of a
        // broken pipe before the session was registered.
        new orca.OrcaFlowException("broken pipe before spawn"),
        """{"fixed":[],"ignored":[]}"""
      ),
      toolConfig = AgentConfig(retrySchedule =
        Schedule.exponentialBackoff(1.milli).maxRetries(1)
      ),
      listener = listener,
      prompts = DefaultPrompts
    )
    val _ = tool.resultAs[FixOutcome].autonomous.run("fix compile errors")
    assertEquals(
      seen.get().reverse.collect { case t: OrcaEvent.TokensUsed => t.attempt },
      List(1)
    )

  // The manifest writer (ADR 0021 §8) needs the wire id known after the
  // backend call returns, so `SessionCommitted` fires post-`runAutonomous`
  // with whatever that call just committed.
  test(
    "autonomous run emits exactly one SessionCommitted with the stub's wire id"
  ):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool =
      new StubTool(new CommittingBackend("wire-committed"), listener = listener)
    val _ = tool.run("prompt")
    val committed = seen.get().collect { case e: OrcaEvent.SessionCommitted =>
      e
    }
    assertEquals(committed.size, 1, committed)
    assertEquals(committed.head.harness, BackendTag.Pi.wireName)
    assertEquals(committed.head.wireId, Some("wire-committed"))
    assertEquals(committed.head.agent, "stub")
    assertEquals(committed.head.role, None)
    assertEquals(committed.head.sessionName, None)

  // The manifest classifies a session as durable off this field alone, so the
  // name a `FlowSession` hands to `runWithSession` has to survive to the event.
  test("a named session's name reaches SessionCommitted"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool =
      new StubTool(new CommittingBackend("wire-named"), listener = listener)
    val _ = tool.autonomous.runWithSession(
      "prompt",
      SessionId.fresh[BackendTag.Pi.type],
      sessionName = Some("coder"),
      config = None,
      emitPrompt = true
    )
    assertEquals(
      seen.get().collect { case e: OrcaEvent.SessionCommitted =>
        e.sessionName
      },
      List(Some("coder"))
    )

  // A turn joins to the session that produced it only if it names that session
  // by the same key `SessionCommitted` is deduplicated under — here the wire id
  // the backend minted during the call, not the client id orca started with.
  test("a turn names its session by the wire id the backend committed"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool =
      new StubTool(new CommittingBackend("wire-joined"), listener = listener)
    val _ = tool.run("prompt")
    assertEquals(
      seen.get().collect { case t: OrcaEvent.TokensUsed => t.session },
      List(Some("wire-joined"))
    )

  // `quietTextTurn` runs `backend.runAutonomous` directly on a fresh session,
  // bypassing `runWithSession` entirely — it must never surface a session to
  // the manifest writer.
  test("quietTextTurn emits no SessionCommitted"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool =
      new StubTool(new CommittingBackend("wire-quiet"), listener = listener)
    val _ = tool.quietTextTurn("internal prompt")
    assert(
      !seen.get().exists(_.isInstanceOf[OrcaEvent.SessionCommitted]),
      s"quietTextTurn must not emit SessionCommitted: ${seen.get()}"
    )

  // A structured resultAs[O] call's closing assistant turn IS the raw JSON
  // payload; the caller re-surfaces it via StructuredResult, so it must not
  // also flow through as an AssistantMessage (double display of the same
  // result — see Conversations.TurnBuffer).
  test(
    "resultAs[O].autonomous.run: the raw JSON payload doesn't echo as an AssistantMessage"
  ):
    val json =
      """{"fixed":["main.rs now depends on untracked src/lib.rs"],"ignored":[]}"""
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(
      new ScriptedDrainBackend(json),
      listener = listener,
      prompts = DefaultPrompts
    )
    val result = tool.resultAs[FixOutcome].autonomous.run("fix compile errors")
    assertEquals(
      result,
      FixOutcome(List("main.rs now depends on untracked src/lib.rs"), Nil)
    )
    val events = seen.get().reverse
    assert(
      events.exists(_.isInstanceOf[OrcaEvent.StructuredResult]),
      s"expected a StructuredResult event: $events"
    )
    assert(
      !events.exists(_ == OrcaEvent.AssistantMessage(json)),
      s"the raw JSON payload must not also surface as an AssistantMessage: $events"
    )

  // Guard against over-filtering: a plain free-text autonomous.run's whole
  // point is to surface its reply, so the AssistantMessage suppression above
  // must be scoped to structured calls only.
  test(
    "plain text autonomous.run still emits its AssistantMessage"
  ):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(
      new ScriptedDrainBackend("plain prose reply"),
      listener = listener,
      prompts = DefaultPrompts
    )
    val reply = tool.run("say hello")
    assertEquals(reply, "plain prose reply")
    assert(
      seen
        .get()
        .reverse
        .contains(OrcaEvent.AssistantMessage("plain prose reply")),
      s"a plain text call must still surface its AssistantMessage: ${seen.get()}"
    )

  // The interactive structured door's closing turn IS the JSON payload too
  // (see the autonomous test above) — `DefaultAgentCall.runInteractiveOnce`
  // must withhold it the same way, while an earlier genuine turn still shows.
  test(
    "resultAs[O].interactive.run: the raw JSON payload doesn't echo as an AssistantMessage; an earlier turn still does"
  ):
    val json =
      """{"fixed":["main.rs now depends on untracked src/lib.rs"],"ignored":[]}"""
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val recordedConvEvents = new java.util.concurrent.atomic.AtomicReference[
      List[ConversationEvent]
    ](Nil)
    val tool = new StubTool(
      new ScriptedInteractiveBackend(
        List(
          ConversationEvent.AssistantTextDelta(
            "Looking at the failing build..."
          ),
          ConversationEvent.AssistantTurnEnd,
          ConversationEvent.AssistantTextDelta(json),
          ConversationEvent.AssistantTurnEnd
        ),
        finalOutput = json,
        schema = Some("{}")
      ),
      listener = listener,
      prompts = DefaultPrompts,
      interaction = new RecordingInteraction(recordedConvEvents)
    )
    val result =
      tool.resultAs[FixOutcome].interactive.run("fix compile errors")
    assertEquals(
      result,
      FixOutcome(List("main.rs now depends on untracked src/lib.rs"), Nil)
    )
    val events = seen.get().reverse
    assert(
      events.exists(_.isInstanceOf[OrcaEvent.StructuredResult]),
      s"expected a StructuredResult event: $events"
    )
    assert(
      events.exists(
        _ == OrcaEvent.AssistantMessage("Looking at the failing build...")
      ),
      s"the earlier turn's prose must still surface: $events"
    )
    assert(
      !events.exists(_ == OrcaEvent.AssistantMessage(json)),
      s"the raw JSON payload must not also surface as an AssistantMessage: $events"
    )
    assert(
      recordedConvEvents.get().isEmpty,
      "the driving Interaction must not see any assistant-text " +
        s"ConversationEvents (both turns are pure prose): ${recordedConvEvents.get()}"
    )

  // Guard against over-filtering the interactive door too: a free-form turn
  // (no outputSchema) has no payload to de-dup against, so its prose must
  // still surface.
  test(
    "interactive.run: a free-text turn (no outputSchema) still emits its AssistantMessage"
  ):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val tool = new StubTool(
      new ScriptedInteractiveBackend(
        List(
          ConversationEvent.AssistantTextDelta("hello there"),
          ConversationEvent.AssistantTurnEnd
        ),
        finalOutput = "\"hello there\"",
        schema = None
      ),
      listener = listener,
      prompts = DefaultPrompts,
      interaction = new RecordingInteraction(
        new java.util.concurrent.atomic.AtomicReference[List[
          ConversationEvent
        ]](
          Nil
        )
      )
    )
    val result = tool.resultAs[String].interactive.run("say hello")
    assertEquals(result, "hello there")
    assert(
      seen.get().reverse.contains(OrcaEvent.AssistantMessage("hello there")),
      s"a free-text interactive turn must still surface its AssistantMessage: ${seen.get()}"
    )

  // An explicit `Some(...)` config wholly replaces the tool-level config (no
  // per-field merge); omission (`None`) inherits it — see
  // `BaseAgent.effectiveConfig`.
  test(
    "run(config = Some(AgentConfig())) wholly replaces the tool-level config"
  ):
    val backend = new RecordingConfigBackend
    val toolConfig = AgentConfig(
      model = Some(Model("tool-level-model")),
      systemPrompt = Some("tool-level-prompt")
    )
    val tool = new StubTool(backend, toolConfig)
    val _ = tool.run("prompt", config = Some(AgentConfig()))
    assertEquals(
      backend.lastConfig,
      Some(AgentConfig()),
      "an explicit Some(...) must wipe the tool-level config, not merge with it"
    )

  test("run() with config omitted falls back to the tool-level config"):
    val backend = new RecordingConfigBackend
    val toolConfig = AgentConfig(
      model = Some(Model("tool-level-model")),
      systemPrompt = Some("tool-level-prompt")
    )
    val tool = new StubTool(backend, toolConfig)
    val _ = tool.run("prompt")
    assertEquals(backend.lastConfig, Some(toolConfig))

  private class StubTool(
      backend: AgentBackend[BackendTag.Pi.type],
      toolConfig: AgentConfig = AgentConfig(),
      listener: OrcaListener = OrcaListener.noop,
      // Most tests never call `prompts.autonomous` (StubPrompts throws), so it
      // defaults to the stub; tests that actually run a resultAs/run call pass
      // DefaultPrompts to get a real prompt string.
      prompts: Prompts = StubPrompts,
      // Most tests never drive an interactive call (StubInteraction throws);
      // the interactive tests pass a `RecordingInteraction` that actually
      // pulls `conversation.events`.
      interaction: Interaction = StubInteraction
  ) extends BaseAgent[BackendTag.Pi.type, Agent[BackendTag.Pi.type]](
        backend,
        toolConfig,
        prompts,
        listener,
        interaction
      ):
    val name: String = "stub"
    // A new instance over the same backend, as production implementations do, so
    // the copyTool-after-close test exercises the real leak shape rather than a
    // same-instance alias.
    protected def copyTool(
        config: AgentConfig = toolConfig,
        name: String = name,
        role: Option[String] = None
    ): Agent[BackendTag.Pi.type] =
      new StubTool(backend, config, listener, prompts, interaction)

  /** Records the `AgentConfig` the framework actually resolved and passed to
    * the backend, so tests can assert on it directly.
    */
  private class RecordingConfigBackend
      extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    var lastConfig: Option[AgentConfig] = None
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      lastConfig = Some(config)
      AgentResult(
        WireSessionId[BackendTag.Pi.type]("server-wire-id"),
        "out",
        Usage.empty
      )
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  /** Fails every turn with `error`, so the fallback/accounting paths around a
    * failed `runAutonomous` can be exercised without a live backend.
    */
  private class FailingBackend(error: Throwable)
      extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] = throw error
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  /** Emits the streaming display events a real drain would (a tool line and the
    * assistant's reply) so the quiet-turn test can assert they are filtered.
    */
  private class NoisyBackend
      extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      events.onEvent(OrcaEvent.ToolUse("Read", "{}"))
      events.onEvent(OrcaEvent.AssistantMessage("short-label"))
      AgentResult(
        WireSessionId[BackendTag.Pi.type]("wire"),
        "short-label",
        Usage.empty
      )
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  /** Drives a real `Conversations.runAutonomous` (rather than returning a
    * canned `AgentResult` directly, as the other stub backends do) so the
    * TurnBuffer withholding logic actually runs: each reply streams as a single
    * completed assistant turn, exactly the shape a real backend produces for a
    * one-turn structured reply. Threads the caller's `outputSchema` through
    * unchanged, so a plain `run()` call (which passes `None`) exercises
    * non-structured mode and a `resultAs[O]` call (which passes `Some(...)`)
    * exercises structured mode.
    *
    * `replies` are answered one per call, so a retried call can be scripted
    * with an output that won't parse followed by one that will.
    */
  private class ScriptedDrainBackend(replies: String*)
      extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    private val remaining = replies.iterator
    val workDir: os.Path = os.pwd
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      val schema = outputSchema
      // A test scripted with too few replies should say so, rather than
      // surface the iterator's NoSuchElementException — and only once the
      // retry schedule runs out, since the policy retries anything but
      // `AgentTurnFailed`.
      if !remaining.hasNext then
        throw new IllegalStateException("scripted replies exhausted")
      val reply = remaining.next()
      Conversations.runAutonomous(
        session,
        sessions,
        config.autoApprove,
        events
      ):
        new Conversation[BackendTag.Pi.type]:
          val outputSchema: Option[String] = schema
          def events(using ox.Ox): Iterator[ConversationEvent] =
            Iterator(
              ConversationEvent.AssistantTextDelta(reply),
              ConversationEvent.AssistantTurnEnd
            )
          def awaitResult()(using ox.Ox) =
            Right(
              AgentResult(
                WireSessionId[BackendTag.Pi.type]("scripted-wire"),
                reply,
                Usage.empty
              )
            )
          def canAskUser: Boolean = false
          def cancel(): Unit = ()
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException

  /** Collects the `Step` lines a run showed, in order — the notice's
    * user-facing channel.
    */
  private class StepRecorder:
    private val steps =
      new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val listener: OrcaListener = event =>
      event match
        case OrcaEvent.Step(message) =>
          val _ = steps.updateAndGet(message :: _)
        case _ => ()
    def seen: List[String] = steps.get().reverse

  /** Gates nothing mechanically, whichever way the turn dispatches — the
    * condition `EnforcementNotice` exists to report.
    */
  private class UngatedBackend(replies: String*)
      extends ScriptedDrainBackend(replies*):
    override def enforcementCell(
        tools: ToolSet,
        autoApprove: AutoApprove,
        dispatch: TurnDispatch
    ): EnforcementCell =
      EnforcementCell(Enforcement.PromptOnly, "the prompt is the whole gate")

  /** codex's shape: the sandbox flags ride on the spawn only, so every tier's
    * answer weakens once the session is resumed — from a gate to prose on the
    * read-only tiers, and from an approximation to nothing on `Full`. Only a
    * call that classifies per attempt sees the second answer.
    */
  private class WeakerOnResumeBackend(replies: String*)
      extends ScriptedDrainBackend(replies*):
    override def enforcementCell(
        tools: ToolSet,
        autoApprove: AutoApprove,
        dispatch: TurnDispatch
    ): EnforcementCell = dispatch match
      case TurnDispatch.Fresh =>
        tools match
          case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
            EnforcementCell(
              Enforcement.Hard,
              "the spawn carries the sandbox flag"
            )
          case ToolSet.Full =>
            EnforcementCell(
              Enforcement.SandboxApprox,
              "the spawn's sandbox is coarser than the list"
            )
      case TurnDispatch.Resumed =>
        tools match
          case ToolSet.ReadOnly | ToolSet.NetworkOnly =>
            EnforcementCell(
              Enforcement.PromptOnly,
              "the resume carries no flag"
            )
          case ToolSet.Full =>
            EnforcementCell(Enforcement.Ignored, "the resume carries no flag")

  /** Throws `error` on its first turn and scripts the rest — an attempt that
    * dies before reaching the model, which consumes none of `replies`.
    */
  private class FailFirstBackend(error: Throwable, replies: String*)
      extends ScriptedDrainBackend(replies*):
    private var thrown = false
    override def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      if thrown then
        super.doRunAutonomous(prompt, session, config, events, outputSchema)
      else
        thrown = true
        throw error

  /** Interactive counterpart to [[ScriptedDrainBackend]]: `runInteractive`
    * replays `scripted` verbatim (no drain of its own — the interactive door
    * has none) and `awaitResult` returns `finalOutput` under the given
    * `schema`, so `DefaultAgentCall.runInteractiveOnce`'s own turn-withholding
    * runs against a stream shaped like a real backend's.
    */
  private class ScriptedInteractiveBackend(
      scripted: List[ConversationEvent],
      finalOutput: String,
      schema: Option[String]
  ) extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] = throw new UnsupportedOperationException
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      new Conversation[BackendTag.Pi.type]:
        val outputSchema: Option[String] = schema
        def events(using ox.Ox): Iterator[ConversationEvent] = scripted.iterator
        def awaitResult()(using ox.Ox) =
          Right(
            AgentResult(
              WireSessionId[BackendTag.Pi.type]("scripted-wire"),
              finalOutput,
              Usage.empty
            )
          )
        def canAskUser: Boolean = false
        def cancel(): Unit = ()

  /** A driving `Interaction` that actually pulls `conversation.events` —
    * recording every one it sees into `seen`, so tests can assert what does and
    * doesn't reach the channel — before returning the awaited result. Unlike
    * [[StubInteraction]], which never touches the stream.
    */
  private class RecordingInteraction(
      seen: java.util.concurrent.atomic.AtomicReference[List[ConversationEvent]]
  ) extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](conversation: Conversation[B])(using
        ox.Ox
    ): AgentResult[B] =
      conversation.events.foreach(e => { val _ = seen.updateAndGet(e :: _) })
      conversation.awaitResult() match
        case Right(r) => r
        case Left(c)  => throw c

  /** Mimics a real subprocess backend's `drainAndCommit`: returns a canned
    * result and immediately commits the session as resumable, so
    * `Agent.resumeWireId` reports `wireId` once `runAutonomous` returns.
    */
  private class CommittingBackend(wireId: String)
      extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.durable(IdScheme.ServerMinted, _ => true)
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      val result = AgentResult(
        WireSessionId[BackendTag.Pi.type](wireId),
        "out",
        Usage.empty
      )
      sessions.commitAfterDrain(session, result.wireId)
      result
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  private object StubBackend
      extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      AgentResult(
        WireSessionId[BackendTag.Pi.type]("server-wire-id"),
        "out",
        Usage.empty
      )
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  private class RecordingCloseBackend
      extends AgentBackend[BackendTag.Pi.type]
      with StubEnforcementCell[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    var closeCount: Int = 0
    override def close(): Unit = closeCount += 1
    def doRunAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] = ???
    def doRunInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] = ???
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  private object StubPrompts extends Prompts:
    def autonomous(
        input: String,
        outputSchema: String,
        config: AgentConfig,
        mode: StructuredOutputMode
    ): String = ???
    def interactive(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def retry(failedResponse: String, parseError: String): String = ???

  private object StubInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](conversation: Conversation[B])(using
        ox.Ox
    ): AgentResult[B] =
      ???
