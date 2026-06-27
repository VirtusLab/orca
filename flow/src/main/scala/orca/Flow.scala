package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.events.OrcaEvent
import orca.agents.JsonData
import orca.progress.StageEntry
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

private val log = LoggerFactory.getLogger("orca.flow")

/** Run `body` as a named, resumable, committing stage (ADR 0018 §2.1).
  *
  * Control flow:
  *
  *   - Compute the stage id `name#occurrence`, where `occurrence` counts prior
  *     same-named stages in this run.
  *   - Resume: if the progress log already holds an entry for this id whose
  *     stored JSON decodes to `T`, emit StageStarted/StageCompleted and return
  *     the decoded value without running `body`. A decode failure (the stage's
  *     result type changed under this id) is fail-safe: fall through and run.
  *   - Run: emit StageStarted, supply an `InStage` token, evaluate `body`.
  *   - Record & commit: append a `StageEntry(id, name, resultJson)` to the log,
  *     force-add the log file, then commit (the commit also `add -A`s code
  *     changes, so a stage yields one commit covering code + progress). Emit
  *     StageCompleted.
  *
  * Error handling mirrors `fail`/tool-adapter semantics: a non-fatal failure in
  * `body` emits an Error event (once — `fail` and malformed-output already
  * carry their own emission state) and re-raises. Fatal throwables propagate
  * unreported, as they signal shutdown rather than a stage outcome.
  */
def stage[T: JsonData](
    name: String,
    commitMessage: Option[T => String] = None
)(body: InStage ?=> T)(using fc: FlowControl): T =
  val id = s"$name#${fc.nextOccurrence(name)}"
  resumeFrom(id, name).getOrElse(runStage(id, name, commitMessage)(body))

/** Try to skip the stage by replaying a recorded result. `Some(value)` when the
  * log holds an entry for `id` that decodes to `T`; `None` when there's no
  * entry or it no longer decodes (fail-safe: the caller then re-runs the body).
  */
private def resumeFrom[T: JsonData](id: String, name: String)(using
    fc: FlowControl
): Option[T] =
  fc.progressStore
    .load()
    .flatMap(_.entries.find(_.id == id))
    .flatMap: entry =>
      // Only the decode is fail-safe: a decode failure means the stage's result
      // type changed under this id, so we fall through and re-run (None). The
      // emits must NOT be inside the try — if `emit` threw, a catch here would
      // spuriously re-run an already-recorded stage.
      val decoded =
        try
          Some(
            readFromString[T](entry.resultJson)(using summon[JsonData[T]].codec)
          )
        catch case NonFatal(_) => None
      decoded.map: value =>
        fc.emit(OrcaEvent.StageStarted(name))
        fc.emit(OrcaEvent.Step(s"Resuming '$name' from recorded result"))
        fc.emit(OrcaEvent.StageCompleted(name))
        value

/** Run the body fresh, then record its result and commit (steps 3–4 above). */
private def runStage[T: JsonData](
    id: String,
    name: String,
    commitMessage: Option[T => String]
)(body: InStage ?=> T)(using fc: FlowControl): T =
  fc.emit(OrcaEvent.StageStarted(name))
  try
    val result =
      given InStage = InStage.unsafe
      body
    recordAndCommit(id, name, result, commitMessage)
    fc.emit(OrcaEvent.StageCompleted(name))
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
        case mao: orca.agents.MalformedAgentOutputException =>
          fc.emit(OrcaEvent.Error(formatMalformedOutput(name, mao)))
          e.alreadyEmitted = true
        case _ if e.alreadyEmitted => ()
        case _ =>
          fc.emit(
            OrcaEvent.Error(
              s"Stage '$name' failed: ${throwableMessage(e, firstLineOnly = true)}"
            )
          )
          e.alreadyEmitted = true
      throw e
    case NonFatal(e) =>
      fc.emit(
        OrcaEvent.Error(
          s"Stage '$name' failed: ${throwableMessage(e, firstLineOnly = true)}"
        )
      )
      throw e

/** Append the stage's result to the log and commit code + log as one commit.
  * The progress file is force-added (so it lands even when `.orca/` is
  * gitignored); `git.commit`'s own `add -A` picks up any code changes. The log
  * always changed, so a `NothingToCommit` is unexpected — logged at DEBUG (so
  * it's observable) rather than failed, since the recorded result is what
  * matters for resume.
  */
private def recordAndCommit[T: JsonData](
    id: String,
    name: String,
    result: T,
    commitMessage: Option[T => String]
)(using fc: FlowControl): Unit =
  val resultJson = writeToString(result)(using summon[JsonData[T]].codec)
  // Deliberately mints a fresh runtime `InStage` rather than threading the
  // body's token: recording + committing the stage result is the runtime's own
  // privileged step, not part of the user body.
  given InStage = InStage.unsafe
  // Capture the code diff BEFORE force-adding the progress file so the LLM
  // sees only the body's substantive changes, not the orca bookkeeping.
  val message = commitMessage.map(_(result)).getOrElse(llmCommitMessage(name))
  fc.progressStore.appendEntry(StageEntry(id, name, resultJson))
  fc.git.forceAdd(fc.progressStore.path)
  // The log always changed, so a clean tree here is unexpected (a prior partial
  // run may already have committed this entry). Surface it at DEBUG so it's
  // observable rather than silent; never fail the stage over it.
  fc.git.commit(message) match
    case Right(()) => ()
    case Left(_) =>
      log.debug("stage {} commit was empty (already recorded?)", name)

/** Generate a commit message via `llm.cheap` from the current working-tree
  * diff. The diff is captured before the progress file is force-added, so it
  * reflects only code changes the stage body produced. Falls back to `"stage:
  * <name>"` when the diff is empty, the LLM returns blank, or any `NonFatal` is
  * thrown — committing must never break. Only called when the caller supplied
  * no explicit `commitMessage`.
  */
private def llmCommitMessage(
    name: String
)(using fc: FlowControl, ev: InStage): String =
  val fallback = s"stage: $name"
  // `git.diff` is a read and shouldn't fail, but stay defensive: a commit
  // message must never break a stage. The cheap LLM call is guarded by
  // `cheapOneShot` itself.
  val diff =
    try fc.git.diff()
    catch case NonFatal(_) => ""
  if diff.isBlank then fallback
  else
    fc.llm.cheapOneShot(
      s"Write a concise one-line git commit message (imperative mood, ≤72 chars) for this diff:\n\n$diff",
      fallback
    )

/** A throwable's human message: its `getMessage` (or the class name when
  * blank), optionally collapsed to its first line. Shared by `stage` (first
  * line, for a tidy one-line `✖`) and the flow boundary (whole message, so
  * multi-line diagnostics like opencode's start-failure stderr survive).
  */
private[orca] def throwableMessage(
    e: Throwable,
    firstLineOnly: Boolean = false
): String =
  val msg = Option(e.getMessage).filter(_.nonEmpty)
  val picked =
    if firstLineOnly then msg.flatMap(_.linesIterator.nextOption()) else msg
  picked.getOrElse(e.getClass.getName)

private def formatMalformedOutput(
    stage: String,
    e: orca.agents.MalformedAgentOutputException
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

/** Show a progress line without checkpointing: no stage, id, commit, or log
  * entry (ADR 0018 §2.1). Needs only `FlowContext`, so it's callable anywhere —
  * outside a stage, or inside a fork.
  */
def display(message: String)(using ctx: FlowContext): Unit =
  ctx.emit(OrcaEvent.Step(message))

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
