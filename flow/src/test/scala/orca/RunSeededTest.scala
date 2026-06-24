package orca

import munit.FunSuite
import orca.events.EventDispatcher
import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId,
  ToolSet
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
    new TestFlowControl(new EventDispatcher(Nil), git, store, "p")

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

  test(
    "no recorded seed for session: runs without crash, prompt still present"
  ):
    // Session id not in the log -> seed treated as empty
    val fc = makeControl(sessions = Nil)
    val llm = new StubLlmForSeeded(existsResult = false)
    val originalPrompt = "do something"
    val _ = llm.runSeeded(originalPrompt, testSession)(using fc)
    val prompt = llm.capturedPrompt.getOrElse(fail("no prompt captured"))
    assert(
      prompt.contains(originalPrompt),
      s"prompt must contain original prompt even with no seed; got: $prompt"
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

  test("LlmTool.sessionExists: default on trait returns false"):
    val llm = new StubLlmForSeeded(existsResult = false)
    assert(!llm.sessionExists(testSession))

  test("LlmTool.sessionExists: can return true when overridden"):
    val llm = new StubLlmForSeeded(existsResult = true)
    assert(llm.sessionExists(testSession))
