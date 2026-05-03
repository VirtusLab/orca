package orca.subprocess

import java.util.concurrent.atomic.AtomicReference

case class CliCall(
    args: List[String],
    stdin: String,
    env: Map[String, String],
    cwd: os.Path
)

/** A `CliRunner` that returns a pre-configured response and records every `run`
  * invocation for later assertions. The response can be swapped via
  * `setResponse` so tests can simulate external state changes across polls.
  * Tests that need a piped subprocess construct `FakePipedCliProcess` directly
  * — this stub's `spawnPiped` is a no-op that throws.
  */
class StubCliRunner(
    initialResponse: CliResult = CliResult(0, "", "")
) extends CliRunner:
  private val current: AtomicReference[CliResult] =
    AtomicReference(initialResponse)
  private val recordedCalls: AtomicReference[List[CliCall]] =
    AtomicReference(Nil)

  def setResponse(r: CliResult): Unit = current.set(r)

  // Calls are prepended (newest-first) and reversed for chronological access.
  def calls: List[CliCall] = recordedCalls.get().reverse
  def lastCall: Option[CliCall] = recordedCalls.get().headOption

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    val _ =
      recordedCalls.updateAndGet(cs =>
        CliCall(args.toList, stdin, env, cwd) :: cs
      )
    current.get()

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  ): PipedCliProcess =
    throw new UnsupportedOperationException(
      "StubCliRunner does not support spawnPiped; use FakePipedCliProcess directly"
    )
