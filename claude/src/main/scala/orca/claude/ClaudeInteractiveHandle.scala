package orca.claude

import orca.{
  Backend,
  InteractiveHandle,
  LlmResult,
  OrcaFlowException,
  SessionId,
  Usage
}
import orca.subprocess.CliProcess

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Handle over a running interactive `claude` session. Waits for the Stop hook
  * to write `/tmp/orca-<session-id>.json`, then signals SIGINT so the user is
  * released from the Claude REPL.
  */
class ClaudeInteractiveHandle(
    process: CliProcess,
    sessionId: SessionId[Backend.ClaudeCode.type],
    pollInterval: FiniteDuration = 100.millis
) extends InteractiveHandle[Backend.ClaudeCode.type]:

  private val sentinelPath: os.Path =
    ClaudeStopHook.sentinelPath(SessionId.value(sessionId))

  def awaitTermination(): LlmResult[Backend.ClaudeCode.type] =
    while !os.exists(sentinelPath) && process.isAlive do
      Thread.sleep(pollInterval.toMillis)
    if process.isAlive then process.sendSigInt()
    val _ = process.waitForExit()
    Option.when(os.exists(sentinelPath))(os.read(sentinelPath)) match
      case Some(payload) =>
        LlmResult(sessionId = sessionId, output = payload, usage = Usage.empty)
      case None =>
        throw OrcaFlowException(
          s"claude interactive session ended without writing $sentinelPath"
        )
