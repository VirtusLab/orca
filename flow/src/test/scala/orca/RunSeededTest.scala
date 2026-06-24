package orca

import munit.FunSuite
import orca.backend.{Conversation, Interaction, LlmBackend, LlmResult}
import orca.events.OrcaListener
import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  Prompts,
  SessionId,
  ToolSet,
  BaseLlmTool
}
import orca.progress.{ProgressHeader, ProgressStore, StageEntry, SessionRecord}

/** Tests for `llm.runSeeded` (ADR 0018 §2.6, task D-seed).
  *
  * Each test scenario uses a [[StubLlmForSeeded]] whose `sessionExists` and
  * `autonomous.run` behaviours are injected at construction time, and whose
  * `capturedPrompt` lets tests assert what the prompt looked like after
  * preamble/seed composition.
  */
class RunSeededTest extends FunSuite:

  /** A fixed session id used across all tests; avoids UUID randomness in
    * assertions and lets `makeControl` pre-populate the log without forward
    * references.
    */
  private val testSessionId = "test-session-uuid-1234"
  private val testSession: SessionId[BackendTag.ClaudeCode.type] =
    SessionId[BackendTag.ClaudeCode.type](testSessionId)

  /** Controllable LlmTool stub for seeded-run tests.
    *
    * @param existsResult
    *   The value `sessionExists` returns — set `true` to exercise the "live
    *   session" branch, `false` to exercise the re-seed path.
    * @param runResult
    *   The text `autonomous.run` echoes back.
    */
  private class StubLlmForSeeded(
      existsResult: Boolean,
      runResult: String = "ok"
  ) extends LlmTool[BackendTag.ClaudeCode.type]:
    val name: String = "stub-seeded"

    private var _capturedPrompt: Option[String] = None

    /** The prompt the stub's `autonomous.run` actually received. */
    def capturedPrompt: Option[String] = _capturedPrompt

    override def sessionExists(
        session: SessionId[BackendTag.ClaudeCode.type]
    ): Boolean = existsResult

    val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      new AutonomousTextCall[BackendTag.ClaudeCode.type]:
        def run(
            prompt: String,
            session: SessionId[BackendTag.ClaudeCode.type],
            config: LlmConfig,
            emitPrompt: Boolean
        ): (SessionId[BackendTag.ClaudeCode.type], String) =
          _capturedPrompt = Some(prompt)
          (session, runResult)

    def resultAs[O: JsonData: Announce]
        : LlmCall[BackendTag.ClaudeCode.type, O] =
      ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this

  /** A minimal `LlmBackend` stub whose `sessionExists` returns a fixed value.
    * All other methods throw — they must never be called in these tests.
    */
  private class StubBackend(existsResult: Boolean)
      extends LlmBackend[BackendTag.ClaudeCode.type]:
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type],
        config: LlmConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): LlmResult[BackendTag.ClaudeCode.type] = ???
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type],
        displayPrompt: String,
        config: LlmConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    ): Conversation[BackendTag.ClaudeCode.type] = ???
    override def sessionExists(
        session: SessionId[BackendTag.ClaudeCode.type]
    ): Boolean = existsResult

  /** A minimal `BaseLlmTool`-derived tool backed by [[StubBackend]]. Exercises
    * the real `BaseLlmTool.sessionExists → backend.sessionExists` delegation
    * path in production wiring.
    */
  private class StubBasedTool(backend: StubBackend)
      extends BaseLlmTool[BackendTag.ClaudeCode.type, LlmTool[
        BackendTag.ClaudeCode.type
      ]](
        backend,
        LlmConfig.default,
        NoOpPrompts,
        os.temp.dir(),
        OrcaListener.noop,
        NoOpInteraction
      ):
    val name: String = "stub-based"
    protected def copyTool(
        config: LlmConfig,
        name: String
    ): LlmTool[BackendTag.ClaudeCode.type] = this
    override def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] =
      this
    override def withSystemPrompt(
        p: String
    ): LlmTool[BackendTag.ClaudeCode.type] = this
    override def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
    override def withTools(t: ToolSet): LlmTool[BackendTag.ClaudeCode.type] =
      this
    override def resultAs[O: JsonData: Announce]
        : LlmCall[BackendTag.ClaudeCode.type, O] =
      ???

  /** No-op [[Prompts]] for use in [[StubBasedTool]]; never called in these
    * tests because we only exercise `sessionExists`, not the run path.
    */
  private object NoOpPrompts extends Prompts:
    def autonomous(
        input: String,
        outputSchema: String,
        config: LlmConfig
    ): String = ???
    def interactive(
        input: String,
        outputSchema: String,
        config: LlmConfig
    ): String = ???
    def retry(failedResponse: String, parseError: String): String = ???

  /** No-op [[Interaction]] for use in [[StubBasedTool]]. */
  private object NoOpInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    ): LlmResult[B] = ???

  // ── test helpers ──────────────────────────────────────────────────────────

  /** Build a `TestFlowControl` over a temp dir with a header already written.
    * Optionally writes session records and/or completed stage entries so tests
    * can exercise the "progress preamble" and "recorded seed" paths.
    */
  private def makeControl(
      sessions: List[SessionRecord] = Nil,
      completedStages: List[String] = Nil
  ): TestFlowControl =
    val dir = os.temp.dir()
    val store = ProgressStore.default(dir, "p")
    given InStage = InStage.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    for record <- sessions do store.upsertSession(record)
    for stageName <- completedStages do
      store.appendEntry(
        StageEntry(id = s"$stageName#0", name = stageName, resultJson = "null")
      )
    val git = new orca.tools.OsGitTool(dir)
    new TestFlowControl(new orca.events.EventDispatcher(Nil), git, store, "p")

  // ── tests ─────────────────────────────────────────────────────────────────

  test("live session: prompt forwarded verbatim, no preamble, no seed"):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(SessionRecord(index = 0, id = testSessionId, seed = seed))
    )
    val llm = new StubLlmForSeeded(existsResult = true)
    val originalPrompt = "implement feature X"
    val _ = llm.runSeeded(originalPrompt, testSession)(using fc)
    assertEquals(
      llm.capturedPrompt,
      Some(originalPrompt),
      "live session must pass prompt verbatim"
    )

  test(
    "fresh session (not exists, no completed stages): seed + prompt, no preamble"
  ):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions = List(SessionRecord(index = 0, id = testSessionId, seed = seed))
    )
    val llm = new StubLlmForSeeded(existsResult = false)
    val originalPrompt = "implement feature X"
    val _ = llm.runSeeded(originalPrompt, testSession)(using fc)
    val prompt = llm.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(prompt.contains(seed), s"prompt must contain seed; got: $prompt")
    assert(
      prompt.contains(originalPrompt),
      s"prompt must contain original prompt; got: $prompt"
    )
    assert(
      !prompt.contains("Progress so far"),
      s"no preamble expected on first run; got: $prompt"
    )

  test(
    "lost session on resume (not exists, completed stages): preamble + seed + prompt"
  ):
    val seed = "You are a planning agent."
    val fc = makeControl(
      sessions =
        List(SessionRecord(index = 0, id = testSessionId, seed = seed)),
      completedStages = List("triage", "implement")
    )
    val llm = new StubLlmForSeeded(existsResult = false)
    val originalPrompt = "continue the work"
    val _ = llm.runSeeded(originalPrompt, testSession)(using fc)
    val prompt = llm.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains("Progress so far"),
      s"expected preamble on resume; got: $prompt"
    )
    assert(
      prompt.contains("triage"),
      s"preamble must name completed stage 'triage'; got: $prompt"
    )
    assert(
      prompt.contains("implement"),
      s"preamble must name completed stage 'implement'; got: $prompt"
    )
    assert(prompt.contains(seed), s"prompt must contain seed; got: $prompt")
    assert(
      prompt.contains(originalPrompt),
      s"prompt must contain original prompt; got: $prompt"
    )
    // Contract: preamble precedes seed precedes prompt
    val preambleIdx = prompt.indexOf("Progress so far")
    val seedIdx = prompt.indexOf(seed)
    val promptIdx = prompt.indexOf(originalPrompt)
    assert(
      preambleIdx < seedIdx,
      s"preamble must appear before seed; indices preamble=$preambleIdx seed=$seedIdx"
    )
    assert(
      seedIdx < promptIdx,
      s"seed must appear before prompt; indices seed=$seedIdx prompt=$promptIdx"
    )

  test(
    "no recorded seed for session: captured prompt equals bare original prompt"
  ):
    // Session id not in the log -> seed treated as absent; no preamble
    // (no completed stages) -> prompt must be forwarded verbatim with no
    // leading `---` separator or seed blob.
    val fc = makeControl(sessions = Nil)
    val llm = new StubLlmForSeeded(existsResult = false)
    val originalPrompt = "do something"
    val _ = llm.runSeeded(originalPrompt, testSession)(using fc)
    assertEquals(
      llm.capturedPrompt,
      Some(originalPrompt),
      "no seed + no preamble must produce bare original prompt, not '---\\n\\n' + prompt"
    )

  test(
    "no seed but completed stages: captured prompt has preamble and prompt, no seed blob, no stray separator"
  ):
    // Session exists in log with empty seed; there are completed stages so
    // a preamble is generated.  The prompt must contain the preamble and the
    // original prompt but MUST NOT contain a seed blob (it's empty) and MUST
    // NOT start with `---` (the separator only appears between context and prompt).
    val fc = makeControl(
      sessions = List(SessionRecord(index = 0, id = testSessionId, seed = "")),
      completedStages = List("triage")
    )
    val llm = new StubLlmForSeeded(existsResult = false)
    val originalPrompt = "continue"
    val _ = llm.runSeeded(originalPrompt, testSession)(using fc)
    val prompt = llm.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains("Progress so far"),
      s"expected preamble when stages completed; got: $prompt"
    )
    assert(
      prompt.contains(originalPrompt),
      s"prompt must contain original prompt; got: $prompt"
    )
    assert(
      !prompt.startsWith("---"),
      s"prompt must not start with bare separator; got: $prompt"
    )

  test("runSeeded returns the session id and output from autonomous.run"):
    val seed = "seed text"
    val fc = makeControl(
      sessions = List(SessionRecord(index = 0, id = testSessionId, seed = seed))
    )
    val llm =
      new StubLlmForSeeded(existsResult = false, runResult = "agent output")
    val (returnedSession, output) =
      llm.runSeeded("prompt", testSession)(using fc)
    assertEquals(returnedSession, testSession)
    assertEquals(output, "agent output")

  test(
    "LlmTool.sessionExists: BaseLlmTool delegates to backend (returns false)"
  ):
    // Real delegation: StubBasedTool → BaseLlmTool.sessionExists → StubBackend.sessionExists
    val tool = new StubBasedTool(new StubBackend(existsResult = false))
    assert(!tool.sessionExists(testSession))

  test(
    "LlmTool.sessionExists: BaseLlmTool delegates to backend (returns true)"
  ):
    // Real delegation: StubBasedTool → BaseLlmTool.sessionExists → StubBackend.sessionExists
    val tool = new StubBasedTool(new StubBackend(existsResult = true))
    assert(tool.sessionExists(testSession))
