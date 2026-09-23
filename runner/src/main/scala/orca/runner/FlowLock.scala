package orca.runner

import orca.{OrcaDir, OrcaFlowException, RunKey}
import ox.discard

import java.nio.file.{FileAlreadyExistsException, NoSuchFileException}
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Reentrancy/concurrency guards for `flow(...)`, since a nested or concurrent
  * flow would corrupt the outer flow's git tree (ADR 0018 §6). Three layers,
  * each refusing with an unwrapped [[OrcaFlowException]]:
  *
  *   - A process-wide flag ([[processGuarded]]), held by `flow()` for its whole
  *     call: a nested or concurrent `flow()` in the same JVM is refused before
  *     it touches anything.
  *   - A worktree lock ([[worktreeLocked]]), held while a `--worktree` run
  *     finds or creates its worktree, so two processes starting the same task
  *     never create or repair it at once.
  *   - A `workDir`-keyed lock ([[workdirLocked]]), held by `runFlow` for the
  *     whole run, taken before `FlowLifecycle.setup` mutates git: two processes
  *     never run in one working tree.
  *
  * The two lock files hold the holder's PID; on contention a live PID
  * hard-refuses, a dead one is stolen with a warning. Both live under the
  * self-ignoring `.orca/cache/`, so `git add -A` can never sweep them into a
  * commit: [[orca.OrcaDir.ensureCache]] writes the cache's `.gitignore` before
  * the lock file exists.
  */
private[orca] object FlowLock:

  private val processFlowLock = new AtomicBoolean(false)

  /** Runs `op` as this process's only flow; throws when another is running. */
  def processGuarded[T](op: => T): T =
    if !processFlowLock.compareAndSet(false, true) then
      throw new OrcaFlowException("a flow is already running in this process")
    try op
    finally processFlowLock.set(false)

  /** Runs `op` holding the lock of the `--worktree` run keyed `key` in the
    * repository whose main checkout is `mainCheckout`; throws when a live
    * process holds it.
    */
  def worktreeLocked[T](mainCheckout: os.Path, key: RunKey)(op: => T): T =
    OrcaDir.ensureCache(mainCheckout).discard
    locked(OrcaDir.worktreeLockPath(mainCheckout, key), "for this task")(op)

  /** Runs `op` holding the lock of the run in `workDir`; throws when a live
    * process holds it.
    */
  def workdirLocked[T](workDir: os.Path)(op: => T): T =
    OrcaDir.ensureCache(workDir).discard
    locked(OrcaDir.flowLockPath(workDir), "in this working tree")(op)

  private def locked[T](lockPath: os.Path, where: String)(op: => T): T =
    acquire(lockPath, where)
    try op
    finally
      try os.remove(lockPath): Unit
      catch case NonFatal(_) => ()

  /** Bound on [[acquire]]'s total `CREATE_NEW` attempts — pathological churn
    * must end in a refusal, not a spin.
    */
  private val MaxLockAcquireAttempts = 4

  /** Create the lock file at `lockPath`, holding this process's PID. Refuses
    * when the holder PID is still alive; steals (after a stderr warning) when
    * it isn't. `where` completes "a flow is already running …" in the refusal.
    *
    * The only atomic primitive is `os.write`'s `CREATE_NEW`, so everything
    * funnels back through it: a stale lock is stolen by DELETING it and
    * re-racing the create; a lock that vanishes between the failed create and
    * the read (holder just released) retries the create. Bounded at
    * [[MaxLockAcquireAttempts]]. Two stealers of one stale lock can both win:
    * the slower one's delete removes the lock the faster one just created.
    */
  private def acquire(lockPath: os.Path, where: String): Unit =
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
            s"a flow is already running $where (the lock at " +
              s"$lockPath could not be acquired) — retry, or delete the " +
              "lock if no orca is running"
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
                s"a flow is already running $where (pid ${holderPid.get}) — " +
                  "wait for it to finish, or stop it"
              )
            else
              System.err.println(
                s"[orca] found a stale lock from PID ${holderPid.getOrElse("?")}, " +
                  "which is no longer running — proceeding"
              )
              // Steal = delete + re-race, not `write.over`, which would let
              // every concurrent stealer think it won.
              try os.remove(lockPath): Unit
              catch case NonFatal(_) => ()
              attempt(attemptsLeft - 1)

    attempt(attemptsLeft = MaxLockAcquireAttempts)
