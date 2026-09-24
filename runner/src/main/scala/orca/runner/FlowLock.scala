package orca.runner

import orca.{OrcaDir, OrcaFlowException, RunKey}
import ox.discard

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{LinkOption, StandardOpenOption}
import java.util.concurrent.atomic.AtomicBoolean

/** Reentrancy/concurrency guards for `flow(...)`, since a nested or concurrent
  * flow would corrupt the outer flow's git tree (ADR 0018 §6). Three layers,
  * each refusing with an unwrapped [[OrcaFlowException]]:
  *
  *   - A process-wide flag ([[processGuarded]]), held by `flow()` for its whole
  *     call: a nested or concurrent `flow()` in the same JVM is refused before
  *     it touches anything.
  *   - A worktree lock ([[worktreeLocked]]), held while a `--worktree` run
  *     finds or creates its worktree, so two processes starting the same prompt
  *     never create or repair it at once.
  *   - A `workDir`-keyed lock ([[workdirLocked]]), held by `runFlow` for the
  *     whole run, taken before `FlowLifecycle.setup` mutates git: two processes
  *     never run in one working tree.
  *
  * The two locks are OS file locks, which the OS releases when the holder
  * exits, however it exits: a lock left by a dead process is free. The files
  * hold the holder's PID, named in the refusal. Both live under the
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
    * repository whose main checkout is `mainCheckout`; throws when another
    * process holds it.
    */
  def worktreeLocked[T](mainCheckout: os.Path, key: RunKey)(op: => T): T =
    OrcaDir.ensureCache(mainCheckout).discard
    locked(OrcaDir.worktreeLockPath(mainCheckout, key), "for this prompt")(op)

  /** Runs `op` holding the lock of the run in `workDir`; throws when another
    * process holds it.
    */
  def workdirLocked[T](workDir: os.Path)(op: => T): T =
    OrcaDir.ensureCache(workDir).discard
    locked(OrcaDir.flowLockPath(workDir), "in this working tree")(op)

  /** Runs `op` holding the OS lock on `lockPath`; throws when another process
    * holds it. `where` completes "a flow is already running …" in the refusal.
    *
    * The lock belongs to the whole JVM, and closing any channel on the file
    * drops it (POSIX locks), so a JVM must have at most one channel open on a
    * lock file. [[processGuarded]] ensures it: its one flow takes each lock
    * once.
    */
  private def locked[T](lockPath: os.Path, where: String)(op: => T): T =
    val channel = FileChannel.open(
      lockPath.toNIO,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE,
      LinkOption.NOFOLLOW_LINKS
    )
    // Closing the channel releases the lock. The file stays: deleting it would
    // let a process that opened it just before the delete lock the unlinked
    // file while another locks a fresh one.
    try
      if Option(channel.tryLock()).isDefined then
        recordHolder(channel)
        try op
        finally channel.truncate(0).discard
      else throw refusal(channel, where)
    finally channel.close()

  /** Replaces the file's content with this process's PID, for the refusal a
    * contender reads.
    */
  private def recordHolder(channel: FileChannel): Unit =
    val pid = ProcessHandle.current().pid().toString.getBytes(UTF_8)
    channel.truncate(0).discard
    channel.write(ByteBuffer.wrap(pid), 0).discard

  private def refusal(channel: FileChannel, where: String): OrcaFlowException =
    val holder = holderPid(channel).fold("")(pid => s" (pid $pid)")
    new OrcaFlowException(
      s"a flow is already running $where$holder — wait for it to finish, or " +
        "stop it"
    )

  /** The PID in the lock file: the holder's, or — until the holder records
    * itself — that of a killed earlier holder; absent when an earlier holder
    * released the lock normally.
    */
  private def holderPid(channel: FileChannel): Option[Long] =
    val buffer = ByteBuffer.allocate(MaxPidBytes)
    channel.read(buffer, 0).discard
    new String(buffer.array(), 0, buffer.position(), UTF_8).trim.toLongOption

  /** Room for any `Long` PID in decimal. */
  private val MaxPidBytes = 20
