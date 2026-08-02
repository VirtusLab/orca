package orca.backend

import orca.agents.BackendTag
import orca.subprocess.OsProcCliRunner
import orca.testkit.ProcessProbe.{alive, awaitDead}

import ox.supervised

/** End-to-end teardown of a cancelled turn against a REAL agent process, which
  * is the only way to exercise the tree kill — every fake process has no
  * descendants, so the `destroyForciblyTree` default hides the wiring.
  */
class ConversationTeardownTest extends munit.FunSuite:

  /** Minimal driver that republishes each stdout line, so the test can read the
    * process's output through the conversation surface (the reader fork owns
    * the pipe, so nothing else may read it).
    */
  private class LineEchoingConversation(source: StreamSource)
      extends ForkedConversation[BackendTag.ClaudeCode.type](
        source = source,
        backendName = "fake"
      ):
    val outputSchema: Option[String] = None
    protected def handleLine(line: String): Unit =
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(line))

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
        val conv =
          new LineEchoingConversation(StreamSource.fromProcess(process))
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
