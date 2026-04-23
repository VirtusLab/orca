package orca.subprocess

import java.util.concurrent.atomic.AtomicReference

case class CliCall(
    args: List[String],
    stdin: String,
    env: Map[String, String],
    cwd: os.Path
)

case class SpawnCall(
    args: List[String],
    env: Map[String, String],
    cwd: os.Path
)

/** A `CliRunner` that returns a pre-configured response and records every
  * invocation for later assertions. The response can be swapped via
  * `setResponse` so tests can simulate external state changes across polls.
  * Test helper — mutable state is confined to AtomicReferences.
  */
class StubCliRunner(
    initialResponse: CliResult = CliResult(0, "", ""),
    pipedProcessFactory: () => PipedCliProcess = () => NoopPipedCliProcess
) extends CliRunner:
  private val current: AtomicReference[CliResult] =
    AtomicReference(initialResponse)
  private val recordedCalls: AtomicReference[List[CliCall]] =
    AtomicReference(Nil)
  private val recordedPipedSpawns: AtomicReference[List[SpawnCall]] =
    AtomicReference(Nil)

  def setResponse(r: CliResult): Unit = current.set(r)

  // Calls are prepended (newest-first) and reversed for chronological access.
  def calls: List[CliCall] = recordedCalls.get().reverse
  def lastCall: Option[CliCall] = recordedCalls.get().headOption

  def pipedSpawns: List[SpawnCall] = recordedPipedSpawns.get().reverse
  def lastPipedSpawn: Option[SpawnCall] = recordedPipedSpawns.get().headOption

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
      cwd: os.Path
  ): PipedCliProcess =
    val _ = recordedPipedSpawns.updateAndGet(ss =>
      SpawnCall(args.toList, env, cwd) :: ss
    )
    pipedProcessFactory()

object NoopPipedCliProcess extends PipedCliProcess:
  def sendSigInt(): Unit = ()
  def isAlive: Boolean = false
  def waitForExit(): Int = 0
  def tryExitCode: Option[Int] = Some(0)
  def writeLine(line: String): Unit = ()
  def closeStdin(): Unit = ()
  def stdoutLines: Iterator[String] = Iterator.empty
  def stderrLines: Iterator[String] = Iterator.empty
