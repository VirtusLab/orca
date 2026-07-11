package orca.runner

import orca.OrcaFlowException

import java.nio.file.{FileAlreadyExistsException, NoSuchFileException}
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Reentrancy/concurrency guards for `flow(...)`.
  *
  * A nested or concurrent `flow(...)` today would stash the outer flow's tree,
  * switch branches under it, and `reset --hard` its work (ADR 0018 §6 flags
  * this as accepted-but-deferred scope). Two layers, acquired at the very start
  * of `runFlow` — before `DefaultFlowContext` even exists, since construction
  * is pure but `FlowLifecycle.setup` mutates git immediately after — and
  * released in a `finally` around the whole body:
  *
  *   - A process-wide flag ([[acquireProcess]]): catches same-JVM
  *     nesting/concurrency cheaply, no I/O, before any git mutation.
  *   - A `workDir`-keyed lock file ([[acquireWorkdir]]): catches the
  *     two-process case a same-JVM flag can't see. Content is the holder's PID;
  *     on contention, a live PID hard-refuses, a dead one is stolen with a
  *     warning (crash leftovers must not permanently wedge a working tree).
  *
  * Both guards fire before `ctx` exists, so a violation is a plain, unwrapped
  * exception out of `runFlow` — `flow()`'s stderr backstop (the `NonFatal`
  * catch below `SurfacedFlowFailure`), not the
  * event-reported/`SurfacedFlowFailure` path, is what a caller of `flow()`
  * sees.
  */
private[orca] object FlowLock:

  private val processFlowLock = new AtomicBoolean(false)

  def acquireProcess(): Unit =
    if !processFlowLock.compareAndSet(false, true) then
      throw new OrcaFlowException("a flow is already running in this process")

  def releaseProcess(): Unit = processFlowLock.set(false)

  private def flowLockPath(workDir: os.Path): os.Path =
    workDir / ".orca" / "flow.lock"

  /** Best-effort: keep `git add -A` (the stage runtime's commit,
    * `GitTool.commit`) from ever sweeping the lock file into a commit. ADR 0018
    * only *encourages* projects to gitignore `.orca/`, it doesn't enforce it,
    * so — unlike the progress log, which the stage runtime deliberately
    * force-adds — the lock file needs its own guarantee. Appends to the
    * repo-local, never-committed `<git-common-dir>/info/exclude` rather than a
    * tracked `.gitignore`, so the legal-path commit history is unchanged. The
    * common dir is resolved via `git rev-parse` (not a hand-rolled `.git`
    * check) so a `workDir` that is itself a linked worktree — where `.git` is a
    * pointer file, and `info/` lives in the shared common dir — is covered too.
    * Silently skipped when `workDir` isn't a git repo at all (or git is
    * unavailable): this is belt-and-suspenders, never worth failing the guard
    * over.
    */
  private def excludeLockFromGit(workDir: os.Path): Unit =
    try
      val commonDir = os.Path(
        os.proc(
          "git",
          "rev-parse",
          "--path-format=absolute",
          "--git-common-dir"
        ).call(cwd = workDir)
          .out
          .text()
          .trim
      )
      val excludePath = commonDir / "info" / "exclude"
      val line = ".orca/flow.lock"
      val existing =
        if os.exists(excludePath) then os.read.lines(excludePath)
        else IndexedSeq.empty
      if !existing.contains(line) then
        os.write.append(excludePath, s"$line\n", createFolders = true)
    catch case NonFatal(_) => ()

  /** Bound on [[acquireWorkdir]]'s total `CREATE_NEW` races (the initial try
    * plus every re-race after a losing steal or a holder that released
    * mid-check) — pathological churn must end in a refusal, not a spin.
    */
  private val MaxLockAcquireAttempts = 4

  /** Acquire the `workDir`-keyed lock file, returning its path (release it with
    * [[releaseWorkdir]]). Refuses with the tracker's message when the holder
    * PID is still alive; steals (after a stderr warning) when it isn't.
    *
    * The only atomic primitive here is `os.write`'s `CREATE_NEW`, so everything
    * else must funnel back through it: a stale lock is stolen by DELETING it
    * and re-racing the create (two racing stealers then can't both win — the
    * loser's `CREATE_NEW` fails and it re-reads the winner's live PID), and a
    * lock that vanishes between the failed create and the read (the holder just
    * released) simply retries the create. Bounded at [[MaxLockAcquireAttempts]]
    * total tries (not retries — `attemptsLeft` below counts attempts remaining,
    * including the one about to run).
    */
  def acquireWorkdir(workDir: os.Path): os.Path =
    os.makeDir.all(workDir / ".orca")
    excludeLockFromGit(workDir)
    val lockPath = flowLockPath(workDir)
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
            // The holder released between our failed create and the read —
            // the lock is free again; re-race the atomic create.
            attempt(attemptsLeft - 1)
          case Some(content) =>
            val holderPid = content.toLongOption
            // `isPresent` alone can report a zombie (terminated, unreaped)
            // process as a holder; `isAlive` is the authoritative test.
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
              // Steal = delete + re-race the create; never `write.over`, which
              // would let two concurrent stealers both think they won.
              try os.remove(lockPath): Unit
              catch case NonFatal(_) => ()
              attempt(attemptsLeft - 1)

    attempt(attemptsLeft = MaxLockAcquireAttempts)
    lockPath

  def releaseWorkdir(lockPath: os.Path): Unit =
    try os.remove(lockPath): Unit
    catch case NonFatal(_) => ()
