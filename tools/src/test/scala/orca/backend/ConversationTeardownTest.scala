package orca.backend

import orca.agents.{BackendTag, StructuredOutputMode, WireSessionId}
import orca.events.{TurnDebit, Usage}
import orca.subprocess.{OsProcCliRunner, PipedCliProcess}
import orca.testkit.ProcessProbe.{alive, awaitDead}

import ox.{Ox, supervised, timeout}

import scala.concurrent.duration.*

/** End-to-end teardown of a cancelled turn against a REAL agent process, which
  * is the only way to exercise the tree kill — every fake process has no
  * descendants, so the `destroyForciblyTree` default hides the wiring.
  */
class ConversationTeardownTest extends munit.FunSuite:

  /** Minimal decoder that republishes each stdout line, so the test can read
    * the process's output through the conversation surface (the reader fork
    * owns the pipe, so nothing else may read it), and settles on `done`.
    */
  private object LineEchoing
      extends LineDecoder[BackendTag.ClaudeCode.type, Unit]:
    def backendName: String = "fake"
    def terminalMessageNoun: String = "a settle"
    def init: Unit = ()
    def failedTurnDebit(state: Unit): TurnDebit = TurnDebit.Unobserved
    def line(
        state: Unit,
        line: String
    ): Step[BackendTag.ClaudeCode.type, Unit] =
      if line == "done" then
        Step.Settle(
          (),
          Nil,
          Settled.Succeeded(AgentResult(WireSessionId("s"), "", Usage.empty))
        )
      else Step.continue((), ConversationEvent.AssistantTextDelta(line))

  private def echo(process: PipedCliProcess)(using
      Ox
  ): Conversation[BackendTag.ClaudeCode.type] =
    StreamConversation.start(
      StreamSource.fromProcess(process),
      ConversationSpec(
        openingPrompt = None,
        outputSchema = None,
        structuredOutputMode = StructuredOutputMode.RawText,
        askUser = AskUserChannel.Unavailable,
        onUnsettledEnd = () => ()
      )
    )(_ => LineEchoing)

  test("cancel kills work the agent process spawned, not just its own PID"):
    supervised:
      // Stands in for an agent that backgrounded a build: the `sleep` inherits
      // the stdout pipe, and `wait` keeps the shell alive until the SIGINT, so
      // the descendant is recorded by the signal-time snapshot. It is normally
      // NOT reachable by the time the forcible step runs — the shell dies within
      // milliseconds of the signal and the `sleep` is reparented to init.
      val process = OsProcCliRunner.spawnPiped(
        Seq("bash", "-c", "sleep 30 & echo $!; wait"),
        env = Map.empty,
        cwd = os.pwd,
        pipeStderr = true
      )
      var spawnedPid = 0L
      try
        val conv = echo(process)
        spawnedPid = conv.events.next() match
          case ConversationEvent.AssistantTextDelta(pid) => pid.trim.toLong
          case other => fail(s"expected the spawned PID, got: $other")
        assert(alive(spawnedPid), "the spawned work should be running")

        conv.cancel()

        assert(
          awaitDead(spawnedPid),
          "a cancelled turn must leave no surviving descendant"
        )
      finally
        // A descendant this test failed to reap still holds the stdout pipe
        // open, so without an unconditional kill the scope join would deadlock
        // on the reader instead of reporting the failed assertion.
        process.destroyForciblyTree()
        if spawnedPid > 0 then
          ProcessHandle
            .of(spawnedPid)
            .ifPresent(h => { val _ = h.destroyForcibly() })

  /** Runs `script`, which prints a descendant's PID and then `done`, and
    * asserts the settled turn completes and leaves no descendant behind.
    */
  private def assertSettledTurnEnds(script: String): Unit =
    supervised:
      val process = OsProcCliRunner.spawnPiped(
        Seq("bash", "-c", script),
        env = Map.empty,
        cwd = os.pwd,
        pipeStderr = true
      )
      var spawnedPid = 0L
      try
        val conv = echo(process)
        spawnedPid = conv.events.next() match
          case ConversationEvent.AssistantTextDelta(pid) => pid.trim.toLong
          case other => fail(s"expected the spawned PID, got: $other")
        assert(timeout(10.seconds)(conv.awaitResult()).isRight)
        assert(
          awaitDead(spawnedPid),
          "a settled turn must leave no surviving descendant"
        )
      finally
        process.destroyForciblyTree()
        if spawnedPid > 0 then
          ProcessHandle
            .of(spawnedPid)
            .ifPresent(h => { val _ = h.destroyForcibly() })

  test("a settled turn ends although a descendant holds stderr"):
    assertSettledTurnEnds("sleep 30 >/dev/null & echo $!; echo done; wait")

  test("a settled turn ends although a descendant holds stdout"):
    assertSettledTurnEnds("sleep 30 2>/dev/null & echo $!; echo done; wait")
