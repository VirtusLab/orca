package orca.subprocess

import java.util.concurrent.atomic.AtomicReference

/** CliRunner that hands out a pre-scripted [[FakePipedCliProcess]] on each
  * `spawnPiped` call and records the args. Each prepared process is consumed by
  * exactly one spawn; running out throws. `run` is unsupported.
  *
  * `onSpawn` runs with that call's args while the spawn is in flight — the only
  * way to observe a temp file the subject deletes when the turn finishes.
  */
class SpawnStubCliRunner(
    prepared: List[FakePipedCliProcess],
    onSpawn: List[String] => Unit = _ => ()
) extends CliRunner:
  private val queue = new AtomicReference[List[FakePipedCliProcess]](prepared)
  private val recorded =
    new AtomicReference[List[SpawnStubCliRunner.SpawnCall]](Nil)

  def calls: List[List[String]] = spawnCalls.map(_.args)

  def spawnCalls: List[SpawnStubCliRunner.SpawnCall] = recorded.get().reverse

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    throw new UnsupportedOperationException(
      "SpawnStubCliRunner does not support run; the subject calls spawnPiped"
    )

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  ): PipedCliProcess =
    val _ = recorded.updateAndGet(
      SpawnStubCliRunner.SpawnCall(args.toList, env, cwd, pipeStderr) :: _
    )
    val next = queue
      .getAndUpdate(_.drop(1))
      .headOption
      .getOrElse(
        throw new IllegalStateException("ran out of prepared processes")
      )
    // After the queue pop, so a hook that throws can't leave the queue looking
    // untouched for the next spawn.
    onSpawn(args.toList)
    next

object SpawnStubCliRunner:
  case class SpawnCall(
      args: List[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  )
