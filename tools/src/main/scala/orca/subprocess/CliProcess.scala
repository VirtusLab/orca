package orca.subprocess

import orca.sweep.EnvCookie

trait CliProcess:
  def sendSigInt(): Unit
  def isAlive: Boolean
  def waitForExit(): Int

  /** Forcibly terminate this process (SIGKILL for the OS-backed process; close
    * the pipes for fakes), the backstop after a graceful `sendSigInt` so a
    * reader blocked on stdout always unblocks. Must tolerate calls from any
    * thread and more than once; on the normal path the process has already
    * exited, making this a no-op.
    */
  def destroyForcibly(): Unit

  /** Forcibly terminate this process AND every descendant still reachable from
    * it — the teardown every caller driving a real, spawned process wants. Two
    * reasons a single-PID kill isn't enough: a surviving descendant holds the
    * inherited stdout/stderr pipe write-ends, so a reader blocked on the pipe
    * can be left waiting for EOF; and it keeps running after the turn that
    * started it, free to hold a build lock or write into the working tree the
    * flow is about to commit. A descendant that detached itself into its own
    * session is out of reach — see the OS-backed implementation.
    *
    * Defaults to just this process, which is what fakes with no real children
    * want. Same call-anywhere / idempotent contract as [[destroyForcibly]].
    */
  def destroyForciblyTree(): Unit = destroyForcibly()

/** A spawned process whose stdin / stdout / stderr are connected to pipes the
  * caller controls. The backend writes input via `writeLine` and consumes
  * responses from `stdoutLines`; `closeStdin` signals end-of-input. Every
  * backend closes it as soon as its one message is on the wire, but what the
  * child makes of EOF varies, and it is never a precondition for output:
  * `claude --print --input-format stream-json` answers a turn with stdin open
  * (measured, claude 2.1.220) and exits on the close; codex `exec --json` and
  * gemini take their prompt argv-side, so the close only stops them waiting on
  * a stream they never read from (ADR 0007); opencode's `serve` is a long-lived
  * server ended by a kill, never by EOF.
  *
  * Reads on `stdoutLines` / `stderrLines` block until a line is available or
  * the stream closes. Each iterator must be consumed by a single thread;
  * pending-line buffering is not thread-safe across readers. Implementations
  * memoise the iterator so repeated accesses return the same stream.
  */
trait PipedCliProcess extends CliProcess:

  /** Write one line to the child's stdin and flush it. Throws
    * `java.io.IOException` if called after [[closeStdin]] — the flush is part
    * of the contract precisely so a write to a closed pipe surfaces here rather
    * than sitting in a buffer.
    */
  def writeLine(line: String): Unit
  def closeStdin(): Unit
  def stdoutLines: Iterator[String]
  def stderrLines: Iterator[String]

  /** Non-blocking exit probe: `None` while running, `Some(code)` once exited.
    * The reader fork uses this to tell a clean EOF from a crash.
    */
  def tryExitCode: Option[Int]

  /** Cookie put in this process's environment at spawn;
    * [[orca.sweep.EnvCookieSweep]] sweeps for it. `None` only where there is no
    * OS process to carry one (test fakes) — anything that really spawns must
    * answer `Some`, or its turn's leaked work goes unreported with nothing to
    * show that it was never looked for. Abstract for that reason: a default
    * would make "didn't think about it" and "no process" the same answer.
    */
  def envCookie: Option[EnvCookie]
