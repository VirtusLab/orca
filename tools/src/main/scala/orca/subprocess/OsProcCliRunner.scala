package orca.subprocess

import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.given

/** Runs external commands via os-lib. `check = false` is intentional — callers
  * inspect `exitCode` and `stderr` rather than handle exceptions, since
  * non-zero exits from tools like `claude -p` carry actionable information.
  */
object OsProcCliRunner extends CliRunner:
  // Traces command + flags + exit code to the per-run log. Prompts travel over
  // stdin, not argv, so this doesn't leak prompt bodies (logged separately via
  // the `UserPrompt` event).
  private val log = LoggerFactory.getLogger("orca.proc")

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    // Delegates to QuietProc, which guarantees captured stderr so subprocess
    // output never bypasses the renderer's StatusBar (see [[QuietProc]]).
    log.debug("exec: {} (cwd={})", args.mkString(" "), cwd)
    val result = QuietProc.call(args, cwd = cwd, env = env, stdin = stdin)
    log.debug("exit {}: {}", result.exitCode, args.headOption.getOrElse("?"))
    CliResult(result.exitCode, result.out.text(), result.err.text())

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  ): PipedCliProcess =
    log.debug("spawn: {} (cwd={})", args.mkString(" "), cwd)
    val sub = os
      .proc(args)
      .spawn(
        cwd = cwd,
        env = env,
        stdin = os.Pipe,
        stdout = os.Pipe,
        // Inherit by default: piping risks a buffer-fill hang when the child
        // emits more stderr than the driver drains (claude --verbose).
        // Bounded-stderr backends (codex) opt into piping to surface stderr
        // lines as ConversationEvent.Errors.
        stderr = if pipeStderr then os.Pipe else os.Inherit
      )
    new OsPipedSubProcess(sub, pipeStderr)

private final class OsPipedSubProcess(
    sub: os.SubProcess,
    stderrPiped: Boolean
) extends PipedCliProcess:

  // Memoised so repeated calls return the same iterator, avoiding a second
  // `BufferedReader` leak against the pipe.
  //
  // CRITICAL: must read line-by-line as the child emits — NOT
  // `sub.stdout.lines()`, which reads the whole stream to EOF before returning
  // a `Vector[String]`, turning stream-json into a no-op until the subprocess
  // exits. The underlying `BufferedReader` gives a lazy line-by-line iterator.
  private lazy val stdoutIterator: Iterator[String] =
    sub.stdout.buffered.lines().iterator().asScala
  // Empty iterator when stderr is inherited, so the `PipedCliProcess` contract
  // holds without reading a nonexistent pipe; the actual stream when piped.
  private lazy val stderrIterator: Iterator[String] =
    if stderrPiped then sub.stderr.buffered.lines().iterator().asScala
    else Iterator.empty

  /** Descendants that were alive when the root was signalled. Signalling the
    * root can make it exit, at which point its children are reparented to init
    * and drop out of the root's `descendants()` walk — so a later tree kill
    * would silently miss exactly the processes it exists to reap. Killing a
    * remembered `ProcessHandle` cannot hit an unrelated process that recycled
    * the pid: the JDK signals only if the pid still has the start time the
    * handle was taken with.
    */
  private val signalledDescendants =
    new AtomicReference[List[ProcessHandle]](Nil)

  // Signals the root PID only, deliberately: a spawned child inherits the JVM's
  // process group (`ProcessBuilder` does not setsid), so `kill -INT -<pgid>`
  // would signal orca itself. Descendants are covered by `destroyForciblyTree`,
  // which walks parent→child links instead of the group.
  def sendSigInt(): Unit =
    val _ = signalledDescendants.updateAndGet(_ ++ liveDescendants())
    val _ = QuietProc.call(Seq("kill", "-INT", sub.wrapped.pid.toString))

  def isAlive: Boolean = sub.isAlive()

  def destroyForcibly(): Unit =
    val _ = sub.wrapped.destroyForcibly()

  override def destroyForciblyTree(): Unit =
    // Descendants first, then the root: a child that outlives its parent holds
    // the inherited stdout/stderr pipe write-ends, so killing only the root PID
    // would never EOF a blocked drain.
    // Reachability is best-effort — an already-orphaned process that double-
    // forked and got reparented to init (`nohup … &`, `setsid`) appears in
    // neither the walk nor the snapshot, and would need a dedicated process
    // group at spawn to catch.
    (liveDescendants() ++ signalledDescendants.get())
      .foreach(h => { val _ = h.destroyForcibly() })
    val _ = sub.wrapped.toHandle.destroyForcibly()

  private def liveDescendants(): List[ProcessHandle] =
    sub.wrapped.toHandle.descendants().toList.asScala.toList

  def waitForExit(): Int =
    val _ = sub.join()
    sub.exitCode()

  def writeLine(line: String): Unit =
    sub.stdin.writeLine(line)
    sub.stdin.flush()

  def closeStdin(): Unit = sub.stdin.close()

  def stdoutLines: Iterator[String] = stdoutIterator

  def stderrLines: Iterator[String] = stderrIterator

  def tryExitCode: Option[Int] =
    if sub.isAlive() then None else Some(sub.exitCode())
