package orca.runner

import orca.{OrcaFlowException, RunKey}
import ox.tap

import scala.io.StdIn

/** A separate process taking one of [[FlowLock]]'s locks, since one JVM must
  * not contend with itself for a lock. Once started it prints [[Ready]] and
  * waits for a line on stdin before trying the lock, so several holders can be
  * released at once. It then reports [[Outcome.Acquired]] and holds the lock
  * until its stdin closes, or prints the refusal.
  */
object LockHolder:

  /** Which lock a holder takes. */
  enum Lock:
    case Workdir(workDir: os.Path)
    case Worktree(mainCheckout: os.Path, prompt: String)

  /** What a holder reports after trying its lock. */
  enum Outcome:
    case Acquired
    case Refused(message: String)

  /** Starts a holder of `lock` and waits until it is [[Ready]]. */
  def spawn(lock: Lock): os.SubProcess =
    val args = lock match
      case Lock.Workdir(workDir) => List(workDir.toString)
      case Lock.Worktree(mainCheckout, prompt) =>
        List(mainCheckout.toString, prompt)
    os.proc(
      os.Path(System.getProperty("java.home")) / "bin" / "java",
      "-cp",
      System.getProperty("java.class.path"),
      "orca.runner.LockHolder",
      args
    ).spawn(stderr = os.Inherit)
      .tap: holder =>
        assert(
          holder.stdout.readLine() == Ready,
          "the lock holder did not start"
        )

  /** Lets `holder` try its lock, without waiting for its [[outcome]]. */
  def go(holder: os.SubProcess): Unit =
    holder.stdin.writeLine("")
    holder.stdin.flush()

  /** Blocks until `holder` acquired its lock or was refused; throws when it
    * exited without saying.
    */
  def outcome(holder: os.SubProcess): Outcome =
    Option(holder.stdout.readLine()) match
      case Some(AcquiredLine) => Outcome.Acquired
      case Some(refusal)      => Outcome.Refused(refusal)
      case None =>
        throw new IllegalStateException(
          s"the lock holder exited with code ${holder.wrapped.waitFor()}"
        )

  /** Starts a holder of `lock` and waits until it holds the lock. */
  def acquire(lock: Lock): os.SubProcess =
    spawn(lock).tap: holder =>
      go(holder)
      outcome(holder) match
        case Outcome.Acquired => ()
        case Outcome.Refused(message) =>
          throw new IllegalStateException(
            s"the lock holder was refused: $message"
          )

  /** Kills `holder` as `kill -9` would, and waits until it has exited. */
  def kill(holder: os.SubProcess): Unit =
    val _ = holder.wrapped.destroyForcibly().waitFor()

  private val Ready = "ready"
  private val AcquiredLine = "acquired"

  def main(args: Array[String]): Unit =
    val lock = args.toList match
      case List(workDir) => Lock.Workdir(os.Path(workDir))
      case List(mainCheckout, prompt) =>
        Lock.Worktree(os.Path(mainCheckout), prompt)
      case other => sys.error(s"unexpected arguments: $other")
    println(Ready)
    val _ = StdIn.readLine()
    try
      hold(lock):
        println(AcquiredLine)
        // Returns when the test process closes the pipe, or dies.
        val _ = StdIn.readLine()
    catch case e: OrcaFlowException => println(e.getMessage)

  private def hold(lock: Lock)(op: => Unit): Unit = lock match
    case Lock.Workdir(workDir) => FlowLock.workdirLocked(workDir)(op)
    case Lock.Worktree(mainCheckout, prompt) =>
      FlowLock.worktreeLocked(mainCheckout, RunKey.of(prompt))(op)
