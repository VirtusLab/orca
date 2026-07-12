package orca.subprocess

import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.given

/** Runs external commands via os-lib. `check = false` is intentional — callers
  * inspect `exitCode` and `stderr` rather than handling thrown exceptions,
  * since non-zero exits from tools like `claude -p` carry actionable
  * information.
  */
object OsProcCliRunner extends CliRunner:
  // Trace every external invocation (gh, git, claude, codex) to the per-run
  // log. Prompts travel over stdin, not argv, so this records the command +
  // flags + exit code without leaking prompt bodies; the prompt itself is
  // logged separately via the `UserPrompt` event.
  private val log = LoggerFactory.getLogger("orca.proc")

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    // Delegates to QuietProc for the actual `os.proc(...).call(...)` — it's
    // the one place in `orca.subprocess` that guarantees captured stderr, so
    // subprocess output never bypasses the renderer's StatusBar. See
    // [[QuietProc]] for the full rationale.
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
        // Defaults to Inherit — piping risks a buffer-fill hang when the
        // child emits more stderr than the driver drains in time (claude
        // with --verbose is chatty). Bounded-stderr backends (codex)
        // opt into piping so the driver can surface stderr lines as
        // ConversationEvent.Errors.
        stderr = if pipeStderr then os.Pipe else os.Inherit
      )
    new OsPipedSubProcess(sub, pipeStderr)

private final class OsPipedSubProcess(
    sub: os.SubProcess,
    stderrPiped: Boolean
) extends PipedCliProcess:

  // Memoised so repeated calls return the same iterator, avoiding a
  // second `BufferedReader` leak against the pipe.
  //
  // CRITICAL: must read line-by-line as the child emits — NOT
  // `sub.stdout.lines()`, which dispatches to `geny.ByteData.lines()` and
  // reads the whole stream to EOF before returning a `Vector[String]`. That
  // version turns stream-json into a no-op until the subprocess exits, with
  // all events appearing in a single burst at the end. Using the underlying
  // `BufferedReader` (already wired by os-lib) gives a properly lazy
  // line-by-line iterator.
  private lazy val stdoutIterator: Iterator[String] =
    sub.stdout.buffered.lines().iterator().asScala
  // When stderr is inherited to the parent, expose an empty iterator so
  // the `PipedCliProcess` contract still holds without reading from a
  // nonexistent pipe; when piped, expose the actual stream.
  private lazy val stderrIterator: Iterator[String] =
    if stderrPiped then sub.stderr.buffered.lines().iterator().asScala
    else Iterator.empty

  def sendSigInt(): Unit =
    val _ = QuietProc.call(Seq("kill", "-INT", sub.wrapped.pid.toString))

  def isAlive: Boolean = sub.isAlive()

  def destroyForcibly(): Unit =
    val _ = sub.wrapped.destroyForcibly()

  override def destroyForciblyTree(): Unit =
    // Descendants first, then the root: a launch wrapper that forked the real
    // `serve` child leaves it holding the inherited stdout/stderr pipe
    // write-ends, so killing only the root PID would never EOF a blocked drain.
    // `descendants()` is a best-effort snapshot of live parent→child linkage; it
    // catches a wrapper that stays alive as the worker's parent (the `ollama
    // launch` case). It would NOT catch a wrapper that double-forks and exits,
    // reparenting the worker to init — that would need a process-group kill or a
    // recorded worker PID. No current launcher does that.
    val handle = sub.wrapped.toHandle
    handle.descendants().forEach(h => { val _ = h.destroyForcibly() })
    val _ = handle.destroyForcibly()

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
