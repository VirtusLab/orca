package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import orca.agents.{JsonData, given}
import orca.gitref.{BranchName, CommitHash, Head}
import orca.util.RawJson

/** Whether orca minted [[ProgressHeader.branch]] itself or bound to a
  * pre-existing one. Gates the throwaway-branch auto-delete
  * (`FlowLifecycle.finishBranch`): a branch orca never created must never be
  * deleted, even if a tampered header's `startingCommit` is crafted to make it
  * look throwaway.
  */
enum BranchMode derives JsonData:
  /** Orca minted `branch` itself — the ordinary case. */
  case Created

  /** Orca bound to a pre-existing branch without creating it (skip-branch mode,
    * ADR 0018 amendment).
    */
  case Reused

/** Header capturing the git context in which the progress log was started.
  *
  * `userPrompt` is the full task text: the shell's "Resume interrupted run"
  * offer relaunches the run byte-identically (ADR 0021 §3 amendment), and
  * `RecoveryCheck` refuses a log whose prompt is not the current one.
  * `flowName` is `ORCA_FLOW_NAME`, `None` for a run started outside the shell.
  *
  * `startingCommit` is the commit HEAD pointed at when the run bound its branch
  * — the diff base for a review of everything the whole run changed.
  * `startingBranch` is the branch HEAD was on then, `None` when it was detached
  * — see [[startingHead]].
  */
case class ProgressHeader(
    startingBranch: Option[BranchName],
    branch: BranchName,
    branchMode: BranchMode,
    userPrompt: String,
    flowName: Option[String],
    startingCommit: CommitHash
) derives JsonData:
  /** Where the run started, which a successful run may hand HEAD back to. */
  def startingHead: Head =
    startingBranch.fold(Head.Detached(startingCommit))(Head.OnBranch(_))

/** A single stage's outcome, stored as an already-serialised JSON subtree.
  *
  * `id` is the stage's hierarchical path id — `name#occurrence` segments joined
  * by `/` (e.g. `outer#0/inner#0`), a nested stage prefixed by its enclosing
  * stages' segments (ADR 0018 §2.1). Opaque: only compared for exact equality,
  * never parsed.
  *
  * `resultJson` is type-erased at rest — the log is heterogeneous across stage
  * types; deserialisation to a typed value happens at the stage call site. A
  * [[orca.util.RawJson]], embedded verbatim rather than string-escaped so the
  * persisted file stays directly readable when debugging.
  */
case class StageEntry(id: String, name: String, resultJson: RawJson)
    derives JsonData

/** One run's persisted state: the outcome of each completed stage, and where it
  * published its work ([[PublishedWork]], once the run has).
  *
  * Everything here rides the feature branch, committed at each stage boundary.
  * Machine-local state that would be meaningless in another checkout lives in
  * `.orca/cache/` instead — the durable session records
  * ([[orca.sessions.SessionStore]]) and the attempt manifest.
  */
case class ProgressLog(
    header: ProgressHeader,
    entries: List[StageEntry],
    published: Option[PublishedWork]
) derives JsonData

object ProgressLog:
  /** The derived codec, as `JsonFile` takes it. */
  given codec: JsonValueCodec[ProgressLog] = summon[JsonData[ProgressLog]].codec
