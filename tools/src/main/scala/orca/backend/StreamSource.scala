package orca.backend

import orca.subprocess.PipedCliProcess
import orca.sweep.EnvCookie

/** The line-oriented source a [[orca.backend.ForkedConversation]] drives: a
  * primary line stream, an optional secondary diagnostic stream, a way to stop
  * it, and a terminal status — so one driver serves both a subprocess
  * ([[StreamSource.fromProcess]]) and any other line producer (the OpenCode
  * `GET /event` SSE connection).
  *
  * Thread-safety: [[lines]] and [[errorLines]] are each single-consumer (one
  * reader thread per stream). [[interrupt]] must tolerate calls from any
  * thread, concurrent with iteration and more than once. [[tryExitCode]] is
  * read only after [[lines]] ends.
  */
private[orca] trait StreamSource:
  /** Primary lines in arrival order; the iterator ends when the source closes
    * (process EOF, or the connection closing). Blocks on `next()`.
    */
  def lines: Iterator[String]

  /** Secondary diagnostic lines (a subprocess's stderr). Empty for sources
    * without a separate diagnostic channel.
    */
  def errorLines: Iterator[String]

  /** Stop the source — SIGINT a subprocess, or close a connection. Must make
    * [[lines]] terminate; safe to call more than once. Termination may be a
    * clean EOF (`hasNext` → false) or a thrown read on the next advance (e.g. a
    * closed HTTP stream) — the reader treats either as end-of-stream.
    */
  def interrupt(): Unit

  /** Guaranteed backstop after [[interrupt]]: SIGKILL the subprocess and every
    * descendant it left running / close the connection, so [[lines]] always
    * terminates even if the graceful interrupt didn't take. Default delegates
    * to [[interrupt]] (sufficient for sources whose interrupt already
    * hard-closes); the subprocess source overrides it. Must tolerate calls from
    * any thread and more than once.
    */
  def destroyForcibly(): Unit = interrupt()

  /** Terminal status once [[lines]] has ended: `Some(0)` clean, `Some(n)`
    * non-zero failure, `None` unknown/aborted. A subprocess reports its exit
    * code; a stream that merely closed reports `Some(0)` (a clean end with no
    * further output).
    */
  def tryExitCode: Option[Int]

  /** The cookie of the subprocess this source drives. Defaults to `None`
    * because most sources have no process of their own — OpenCode's SSE
    * connection, whose work runs in a per-run server process rather than a
    * per-turn one, so a per-turn sweep would name the server itself. A source
    * that DOES drive a spawned process must forward its
    * [[orca.subprocess.PipedCliProcess.envCookie]]; leaving the default there
    * silently opts that backend's turns out of the sweep. Today
    * [[StreamSource.fromProcess]] is the only such source.
    */
  def envCookie: Option[EnvCookie] = None

private[orca] object StreamSource:
  /** Adapt a spawned subprocess: stdout/stderr lines, SIGINT, and exit code. */
  def fromProcess(process: PipedCliProcess): StreamSource =
    new StreamSource:
      def lines: Iterator[String] = process.stdoutLines
      def errorLines: Iterator[String] = process.stderrLines
      def interrupt(): Unit = process.sendSigInt()
      // Tree, not PID: a coding-agent CLI spawns its own children (shell tool
      // calls, MCP servers, a build it backgrounded), which inherit the stdout
      // pipe write-end. A root-only kill would orphan that work into the next
      // stage and can leave the reader waiting for EOF. Work the agent
      // deliberately detached stays out of reach either way.
      override def destroyForcibly(): Unit = process.destroyForciblyTree()
      def tryExitCode: Option[Int] = process.tryExitCode
      override def envCookie: Option[EnvCookie] = process.envCookie
