package orca.claude

import java.util.concurrent.atomic.AtomicReference

case class CliCall(
    args: List[String],
    stdin: String,
    env: Map[String, String],
    cwd: os.Path
)

/** A `CliRunner` that returns a pre-configured response and records every
  * invocation for later assertions. Test helper — mutable state is confined to
  * an AtomicReference.
  */
class StubCliRunner(response: CliResult) extends CliRunner:
  private val recorded: AtomicReference[List[CliCall]] =
    AtomicReference(Nil)

  // Calls are prepended (newest-first) and reversed for chronological access.
  def calls: List[CliCall] = recorded.get().reverse
  def lastCall: Option[CliCall] = recorded.get().headOption

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    val _ =
      recorded.updateAndGet(cs => CliCall(args.toList, stdin, env, cwd) :: cs)
    response
