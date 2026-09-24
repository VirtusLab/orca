package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.events.{OrcaEvent, StageOutcome}
import orca.agents.JsonData
import orca.progress.StageEntry
import orca.util.{RawJson, TextUtil}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

private val log = LoggerFactory.getLogger("orca.flow")

/** Run `body` as a named, resumable, committing stage (ADR 0018 §2.1).
  *
  * The stage id is its [[StagePath]]: the enclosing stages' names, then its
  * own, each with an occurrence counting prior same-named stages under the same
  * parent. On resume, if the progress log holds an entry for this id whose JSON
  * decodes to `T`, the decoded value is returned without running `body`; a
  * decode failure (result type changed under this id) falls through and
  * re-runs. Otherwise it runs `body`, appends a `StageEntry(id, resultJson)`,
  * force-adds the log, and commits — the commit also `add -A`s code changes, so
  * a stage yields one commit covering code + progress.
  *
  * A nested stage's commit stages the whole tree, so it sweeps up any
  * uncommitted edits the outer stage's body made before the nesting point. If
  * the flow later fails, the outer stage re-runs against a tree already
  * containing its own partial work (committed under the inner stage's message)
  * — resume is only correct if the outer body is idempotent over its own
  * leftovers. Prefer doing edits inside their own stage rather than around a
  * nested one.
  *
  * A non-fatal failure in `body` emits an Error event once (`fail` and
  * malformed-output carry their own emission state), then the stage's
  * `StageEnded(Failed)`, and re-raises. Fatal throwables propagate unreported
  * and leave the stage unended — they signal shutdown, not a stage outcome.
  */
def stage[T: JsonData](
    name: String,
    commitMessage: Option[T => String] = None
)(body: (InStage, WorkspaceWrite) ?=> T)(using FlowContext, FlowControl): T =
  inStageFrame(name): id =>
    resumeFrom(id).getOrElse(runStage(id, commitMessage)(body))

/** Run `f` with the frame of the stage `name` open, passing its id. */
private def inStageFrame[R](name: String)(f: StagePath.Stage => R)(using
    ctx: FlowContext,
    fc: FlowControl
): R =
  // HEAD is read HERE, before the body: once the body's agent starts
  // committing, the commit this stage began from is no longer recoverable.
  fc.withStage(name, ctx.git.headCommit())(f)

/** Where a stage's result came from: this attempt, or the progress log. */
private[orca] enum Staged[+T]:
  case Fresh(value: T)
  case Replayed(value: T)

  def value: T

/** [[stage]] with a read-only `gate` in front of a fresh run, reporting where
  * the result came from. A stage that replays skips the gate. A gate that
  * answers `Left` stops the stage before anything runs or is recorded; `Right`
  * hands its value to `body`, run and recorded as [[stage]] does with the
  * default commit message.
  *
  * A stage the gate stops still takes its occurrence, so a later same-named
  * stage keeps its id whether or not a resume's gate lets this one run. The
  * gate runs inside the stage's frame, so it must not open stages or sessions.
  */
private[orca] def gatedStage[G, A, T: JsonData](name: String)(
    gate: => Either[G, A]
)(body: A => (InStage, WorkspaceWrite) ?=> T)(using
    FlowContext,
    FlowControl
): Either[G, Staged[T]] =
  inStageFrame(name): id =>
    resumeFrom[T](id) match
      case Some(value) => Right(Staged.Replayed(value))
      case None =>
        gate.map(a => Staged.Fresh(runStage(id, None)(body(a))))

/** [[stage]] reporting where its result came from. */
private[orca] def tracedStage[T: JsonData](name: String)(
    body: (InStage, WorkspaceWrite) ?=> T
)(using FlowContext, FlowControl): Staged[T] =
  gatedStage[Nothing, Unit, T](name)(Right(()))(_ => body).merge

/** Try to skip the stage by replaying a recorded result. `Some(value)` when the
  * log holds an entry for `id` that decodes to `T`; `None` when there's no
  * entry or it no longer decodes (fail-safe: the caller then re-runs the body).
  */
private def resumeFrom[T: JsonData](id: StagePath.Stage)(using
    ctx: FlowContext,
    fc: FlowControl
): Option[T] =
  fc.progressStore
    .load()
    .flatMap(_.entries.find(_.id == id))
    .flatMap: entry =>
      // The try is scoped to the decode only: a decode failure means the
      // stage's result type changed under this id, so fall through and re-run
      // (None).
      val decoded =
        try
          Some(
            readFromString[T](entry.resultJson.value)(using
              summon[JsonData[T]].codec
            )
          )
        catch case NonFatal(_) => None
      decoded.map: value =>
        // A replayed stage announces itself with the stage markers alone: the
        // run already said once what it is resuming from.
        ctx.emit(OrcaEvent.StageStarted(id))
        ctx.emit(OrcaEvent.StageEnded(id, StageOutcome.Replayed))
        value

/** Run the body fresh, then record its result and commit (steps 3–4 above). */
private def runStage[T: JsonData](
    id: StagePath.Stage,
    commitMessage: Option[T => String]
)(body: (InStage, WorkspaceWrite) ?=> T)(using
    ctx: FlowContext,
    fc: FlowControl
): T =
  ctx.emit(OrcaEvent.StageStarted(id))
  try
    val result =
      given InStage = RuntimeInStage.token()
      given WorkspaceWrite = RuntimeInStage.workspaceToken()
      body
    recordAndCommit(id, result, commitMessage)
    ctx.emit(OrcaEvent.StageEnded(id, StageOutcome.Completed))
    result
  catch
    case NonFatal(e) =>
      // A failure from `fail` or a nested stage arrives already reported;
      // anything else (tool adapters, plain RuntimeExceptions) is reported
      // here, else the user would see `exit 1` with no diagnostic.
      val reported = ReportedFailure.reportOnce(e):
        case mao: orca.agents.MalformedAgentOutputException =>
          ctx.emit(OrcaEvent.Error(formatMalformedOutput(id.name, mao)))
        case other =>
          ctx.emit(
            OrcaEvent.Error(
              s"Stage '${id.name}' failed: ${TextUtil.throwableMessage(other, firstLineOnly = true)}"
            )
          )
      ctx.emit(OrcaEvent.StageEnded(id, StageOutcome.Failed))
      throw reported

/** Append the stage's result to the log and commit code + log as one commit.
  * The progress file is force-added (so it lands even when `.orca/` is
  * gitignored); `runtimeGit.commit`'s own `add -A` picks up any code changes.
  */
private def recordAndCommit[T: JsonData](
    id: StagePath.Stage,
    result: T,
    commitMessage: Option[T => String]
)(using ctx: FlowContext, fc: FlowControl): Unit =
  val resultJson = writeToString(result)(using summon[JsonData[T]].codec)
  // Fresh runtime tokens rather than the body's: recording + committing is the
  // runtime's own privileged step, not part of the user body. `InStage` is for
  // the cheap-model commit-message fallback, `WorkspaceWrite` for the
  // progress-store append + git force-add/commit below.
  given InStage = RuntimeInStage.token()
  given WorkspaceWrite = RuntimeInStage.workspaceToken()
  val message =
    commitMessage.map(_(result)).getOrElse(defaultCommitMessage(id.name))
  fc.progressStore.upsertEntry(
    StageEntry(id = id, resultJson = RawJson(resultJson))
  )
  ctx.runtimeGit.forceAdd(fc.progressStore.path)
  // The log always changed, so a clean tree is unexpected (a prior partial run
  // may already have committed this entry): log at DEBUG, never fail the stage.
  ctx.runtimeGit.commit(message) match
    case Right(()) => ()
    case Left(_) =>
      log.debug("stage {} commit was empty (already recorded?)", id.name)

/** Generate a commit message from the current working-tree changes via the
  * coding-role agent's cheap model (`codingAgent.cheapOneShot`), which is sent
  * the bounded summary built by [[BoundedDiff.commitPayload]] rather than the
  * whole diff. The reads span what the stage is about to commit — tracked edits
  * and files new to the repo — and all exclude `.orca/`, so the model sees the
  * change set the commit is about rather than orca's bookkeeping. Falls back to
  * `"stage: <name>"` when there is nothing to describe, the agent returns
  * blank, or any `NonFatal` is thrown — committing must never break, though
  * `cheapOneShot` announces the fallback rather than hiding it. Only called
  * when the caller supplied no explicit `commitMessage`.
  */
private def defaultCommitMessage(
    name: String
)(using ctx: FlowContext, ev: InStage): String =
  val fallback = s"stage: $name"
  // The git reads shouldn't fail, but stay defensive: a commit message must
  // never break a stage. The cheap agent call is guarded by `cheapOneShot`
  // itself.
  val payload =
    try BoundedDiff.commitPayload(ctx.git.pendingChanges())
    catch case NonFatal(_) => ""
  if payload.isBlank then fallback
  else
    ctx.codingAgent.cheapOneShot(
      purpose = "commit message",
      prompt =
        "Write a concise one-line git commit message (imperative mood, ≤72 chars) " +
          "for this change. Describe only what is shown below — it may be " +
          "truncated, so stay general rather than naming specifics you can't " +
          "see. Reply with ONLY the message.\n\n" + payload,
      fallback = fallback
    )

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

/** Show `message` as an error and abort the flow. Enclosing stages and the flow
  * boundary do not report it again, and it does not match a `catch` on
  * `OrcaFlowException`.
  */
def fail(message: String)(using ctx: FlowContext): Nothing =
  ctx.emit(OrcaEvent.Error(message))
  throw ReportedFailure(OrcaFlowException(message))
