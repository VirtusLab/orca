package orca

import orca.events.OrcaEvent

import scala.util.control.NonFatal

/** Wrap `body` as a named stage, emitting StageStarted before and
  * StageCompleted after successful completion. Non-fatal exceptions from `body`
  * trigger an Error event with the stage name and the exception is re-raised.
  * Fatal errors (OOM, InterruptedException, control throwables) propagate
  * without event emission, as they signal shutdown rather than a stage outcome.
  *
  * A body that calls `fail(...)` already emits its own Error, so
  * OrcaFlowException is re-raised without a second Error event.
  */
def stage[T](name: String)(body: => T)(using ctx: FlowContext): T =
  ctx.emit(OrcaEvent.StageStarted(name))
  try
    val result = body
    ctx.emit(OrcaEvent.StageCompleted(name))
    result
  catch
    case e: OrcaFlowException =>
      // Three sub-cases. Malformed-output carries extra render context.
      // Exceptions from `fail(...)` carry `alreadyEmitted = true` and
      // need no further emission. Anything else (tool adapters that
      // throw directly) lands here without a prior emit, and would be
      // invisible if we didn't surface it. After emitting, mark the
      // exception so an enclosing stage / the flow boundary doesn't
      // re-report it as it unwinds.
      e match
        case mao: orca.llm.MalformedAgentOutputException =>
          ctx.emit(OrcaEvent.Error(formatMalformedOutput(name, mao)))
          e.alreadyEmitted = true
        case _ if e.alreadyEmitted => ()
        case _ =>
          ctx.emit(
            OrcaEvent.Error(s"Stage '$name' failed: ${throwableMessage(e, firstLineOnly = true)}")
          )
          e.alreadyEmitted = true
      throw e
    case NonFatal(e) =>
      ctx.emit(
        OrcaEvent.Error(s"Stage '$name' failed: ${throwableMessage(e, firstLineOnly = true)}")
      )
      throw e

/** A throwable's human message: its `getMessage` (or the class name when blank),
  * optionally collapsed to its first line. Shared by `stage` (first line, for a
  * tidy one-line `✖`) and the flow boundary (whole message, so multi-line
  * diagnostics like opencode's start-failure stderr survive).
  */
private[orca] def throwableMessage(
    e: Throwable,
    firstLineOnly: Boolean = false
): String =
  val msg = Option(e.getMessage).filter(_.nonEmpty)
  val picked = if firstLineOnly then msg.flatMap(_.linesIterator.nextOption()) else msg
  picked.getOrElse(e.getClass.getName)

private def formatMalformedOutput(
    stage: String,
    e: orca.llm.MalformedAgentOutputException
): String =
  val snippet =
    val collapsed = e.rawOutput.replaceAll("\\s+", " ").trim
    if collapsed.length <= 200 then collapsed
    else s"${collapsed.take(200)}…"
  s"""Stage '$stage' failed: agent output didn't parse as structured JSON.
     |  cause:  ${e.shortCause}
     |  agent:  $snippet
     |  hint:   tighten the system prompt to enforce JSON-only, or set
     |          ORCA_DEBUG=1 to see the full response.""".stripMargin

def fail(message: String)(using ctx: FlowContext): Nothing =
  ctx.emit(OrcaEvent.Error(message))
  throw new OrcaFlowException(message, alreadyEmitted = true)

/** Pluralize an English noun by appending "s" when `n != 1`. The same count
  * goes into the rendered string (`"1 review comment"` / `"3 review
  * comments"`), so this also encodes the count. Centralised here so callers
  * across packages produce consistent wording.
  */
private[orca] def pluralize(n: Int, singular: String): String =
  s"$n $singular${if n == 1 then "" else "s"}"
