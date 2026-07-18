package orca.runner

import orca.{OrcaDir, OrcaFlowException}

import java.nio.file.{FileAlreadyExistsException, NoSuchFileException}
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Reentrancy/concurrency guards for `flow(...)`, since a nested or concurrent
  * flow would corrupt the outer flow's git tree (ADR 0018 §6). Two layers,
  * acquired at the very start of `runFlow` — before `DefaultFlowContext`
  * exists, since `FlowLifecycle.setup` mutates git immediately after — and
  * released in a `finally`:
  *
  *   - A process-wide flag ([[acquireProcess]]): catches same-JVM
  *     nesting/concurrency cheaply, no I/O.
  *   - A `workDir`-keyed lock file ([[acquireWorkdir]]): catches the
  *     two-process case. Content is the holder's PID; on contention a live PID
  *     hard-refuses, a dead one is stolen with a warning.
  *
  * Both guards fire before `ctx` exists, so a violation is a plain unwrapped
  * exception out of `runFlow`, seen via `flow()`'s stderr backstop rather than
  * the `SurfacedFlowFailure` path.
  */
private[orca] object FlowLock:

  private val processFlowLock = new AtomicBoolean(false)

  def acquireProcess(): Unit =
    if !processFlowLock.compareAndSet(false, true) then
      throw new OrcaFlowException("a flow is already running in this process")

  def releaseProcess(): Unit = processFlowLock.set(false)

  /** Bound on [[acquireWorkdir]]'s total `CREATE_NEW` attempts — pathological
    * churn must end in a refusal, not a spin.
    */
  private val MaxLockAcquireAttempts = 4

  /** Acquire the `workDir`-keyed lock file, returning its path (release it with
    * [[releaseWorkdir]]). Refuses when the holder PID is still alive; steals
    * (after a stderr warning) when it isn't.
    *
    * The lock lives under the self-ignoring `.orca/cache/`, so `git add -A` can
    * never sweep it into a commit: [[orca.OrcaDir.ensureCache]] writes the
    * cache's `.gitignore` before the lock file exists.
    *
    * The only atomic primitive is `os.write`'s `CREATE_NEW`, so everything
    * funnels back through it: a stale lock is stolen by DELETING it and
    * re-racing the create (two racing stealers can't both win — the loser's
    * `CREATE_NEW` fails and it re-reads the winner's live PID); a lock that
    * vanishes between the failed create and the read (holder just released)
    * retries the create. Bounded at [[MaxLockAcquireAttempts]].
    */
  def acquireWorkdir(workDir: os.Path): os.Path =
    val lockPath = OrcaDir.ensureCache(workDir) / "flow.lock"
    val pid = ProcessHandle.current().pid()

    @tailrec def attempt(attemptsLeft: Int): Unit =
      val acquired =
        try
          os.write(lockPath, pid.toString)
          true
        catch case _: FileAlreadyExistsException => false
      if !acquired then
        if attemptsLeft <= 1 then
          throw new OrcaFlowException(
            "a flow is already running in this working tree (the lock at " +
              s"$lockPath could not be acquired)"
          )
        val holderContent =
          try Some(os.read(lockPath).trim)
          catch case _: NoSuchFileException => None
        holderContent match
          case None =>
            // Holder released between our failed create and the read — re-race.
            attempt(attemptsLeft - 1)
          case Some(content) =>
            val holderPid = content.toLongOption
            // `isAlive`, not `isPresent`: the latter reports a zombie
            // (terminated, unreaped) process as a holder. PID reuse can make a
            // stale lock look held — that fails safe (refusal).
            val holderAlive = holderPid.exists(p =>
              ProcessHandle.of(p).map(_.isAlive).orElse(false)
            )
            if holderAlive then
              throw new OrcaFlowException(
                s"a flow is already running in this working tree (pid ${holderPid.get})"
              )
            else
              System.err.println(
                s"[orca] found a stale lock from PID ${holderPid.getOrElse("?")}, " +
                  "which is no longer running — proceeding"
              )
              // Steal = delete + re-race; never `write.over`, which would let
              // two concurrent stealers both think they won.
              try os.remove(lockPath): Unit
              catch case NonFatal(_) => ()
              attempt(attemptsLeft - 1)

    attempt(attemptsLeft = MaxLockAcquireAttempts)
    lockPath

  def releaseWorkdir(lockPath: os.Path): Unit =
    try os.remove(lockPath): Unit
    catch case NonFatal(_) => ()
