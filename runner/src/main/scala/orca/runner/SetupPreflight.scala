package orca.runner

import orca.{OrcaArgs, OrcaDir, OrcaFlowException}
import orca.events.OrcaEvent
import orca.gitref.{BranchName, Head}
import orca.progress.{
  FeatureBranch,
  PeekedLog,
  ProgressScan,
  ProgressStore,
  ScannedProgressLog
}
import orca.runner.FlowLifecycle.SettingsResolution
import orca.tools.RuntimeGit
import orca.util.TextUtil

import scala.util.control.NonFatal

/** What [[SetupPreflight.run]] established. `peeked` is this run's own log as
  * it was before the stash, kept only to be restored ([[PeekedLog]]).
  */
private[runner] case class Preflight(
    startingHead: Head,
    // The protected set both binding arms enforce: the always-protected floor
    // (`main`/`master`) plus the repo's detected default branch.
    protectedBranches: Set[String],
    peeked: PeekedLog,
    tree: TreePlan,
    dirtyCount: Int
)

/** The cleanliness verdict once refusal is off the table. */
private[runner] enum TreePlan:
  case Stash, Keep

/** [[FlowLifecycle.setup]]'s first phase: reads only, so every refusal leaves
  * the tree and branch as the user left them. Nothing here holds a capability
  * token.
  */
private[runner] object SetupPreflight:

  /** Every read-only check and refusal of [[FlowLifecycle.setup]], ahead of any
    * mutation. The dirty-tree question comes last, so a user who answered it is
    * never refused afterwards. `dirtyCount` is taken before that question and
    * may be stale by the time a long-open prompt is answered.
    */
  def run(
      args: OrcaArgs,
      git: RuntimeGit,
      workDir: os.Path,
      stack: SettingsResolution,
      store: ProgressStore,
      emit: OrcaEvent => Unit,
      tty: () => Boolean,
      ask: Int => DirtyTreeChoice
  ): Preflight =
    warnIfSettingsIgnored(git, stack, emit)
    abortIfNoCommits(git)
    val startingHead = git.head()
    // A resumable run may be behind an unreadable log, and the stash cannot
    // make it readable, so refuse before stashing anything.
    val peeked = store
      .peek()
      .fold(reason => throw unreadableLog(store.path, reason), identity)
    startingHead match
      case Head.OnBranch(branch) =>
        abortIfBranchBusy(peeked, store.path, workDir, branch)
      // No branch for another run to have claimed.
      case Head.Detached(_) => ()
    // No recorded default branch leaves just the floor.
    val protectedBranches =
      FeatureBranch.alwaysProtected ++ git
        .defaultBranch()
        .map(_.toLowerCase(java.util.Locale.ROOT))
    abortIfRequestedBranchRefused(args, peeked, git, protectedBranches)
    val dirtyCount = git.dirtyPaths().size
    Preflight(
      startingHead,
      protectedBranches,
      peeked,
      decideTree(args, peeked, dirtyCount, tty, ask),
      dirtyCount
    )

  /** Cleanliness policy (ADR 0018 amendment): the facts feed
    * [[DirtyTreePolicy.decide]] (the decision table lives there), and an abort
    * is thrown here, before anything in the tree is touched. A PRESENT own log
    * — a resume, or one too broken to classify before the stash reverts it —
    * turns the decision away from every keep path.
    */
  private def decideTree(
      args: OrcaArgs,
      peeked: PeekedLog,
      dirtyCount: Int,
      tty: () => Boolean,
      ask: Int => DirtyTreeChoice
  ): TreePlan =
    val ownLogPresent = peeked match
      case PeekedLog.Absent                                  => false
      case PeekedLog.Parseable(_) | PeekedLog.Unparseable(_) => true
    val facts = DirtyTreeFacts(
      ownLogPresent = ownLogPresent,
      skipBranch = args.target.skipBranch,
      keepChanges = args.target.keepChanges,
      dirtyCount = dirtyCount
    )
    DirtyTreePolicy.decide(facts, tty, ask) match
      case DirtyTreeChoice.Stash => TreePlan.Stash
      case DirtyTreeChoice.Keep  => TreePlan.Keep
      case DirtyTreeChoice.Abort =>
        throw new OrcaFlowException(
          s"refusing to start with $dirtyCount uncommitted/untracked " +
            "file(s) in the working tree — commit or stash them yourself, " +
            "or re-run with --keep-changes to leave them in place"
        )

  private[runner] def unreadableLog(
      path: os.Path,
      reason: String
  ): OrcaFlowException =
    new OrcaFlowException(
      s"progress log at $path exists but cannot be read " +
        s"($reason) — it may be a resumable run, so fix its permissions " +
        "to resume it, or delete the file to start fresh"
    )

  /** On an unborn HEAD (`git init`, no commits) every later git call that names
    * `HEAD` exits 128 with git's "ambiguous argument 'HEAD'" fatal, so refuse
    * here with our own message. `headCommit()` is also `None` outside a git
    * repository, hence the message names both cases.
    */
  private def abortIfNoCommits(git: RuntimeGit): Unit =
    if git.headCommit().isEmpty then
      throw new OrcaFlowException(GitPreconditions.needsRepoWithCommit)

  /** Refuses a fresh run's `--branch` with read-only queries.
    * `FlowLifecycle.createRequestedBranch` repeats both checks when it creates
    * the branch.
    */
  private def abortIfRequestedBranchRefused(
      args: OrcaArgs,
      peeked: PeekedLog,
      git: RuntimeGit,
      protectedBranches: Set[String]
  ): Unit =
    peeked match
      case PeekedLog.Absent | PeekedLog.Unparseable(_) =>
        args.branch.foreach: name =>
          val _ = requestedBranch(name, protectedBranches)
          if git.branchExists(name) then throw requestedBranchExists(name)
      case PeekedLog.Parseable(_) => ()

  /** Refuse to start a NEW run on a branch that another run's progress log
    * already claims (ADR 0018 §2.5, R1 amendment).
    *
    * Logs are prompt-keyed, so a differently worded prompt finds this run's own
    * log `Absent` and would otherwise start fresh on whatever branch is checked
    * out — silently sharing it with an interrupted run whose stages are
    * half-done. A log naming the current branch IS such a run: failure teardown
    * keeps the log and stays on the branch, success teardown deletes it.
    *
    * Skipped when this run's own log parses: that is a legitimate resume of the
    * same prompt, and the branch its header names is its own (validated
    * downstream by `bindBranch`) — so other logs naming the branch don't turn a
    * resume into a conflict. A dirty own log that fails to parse is treated
    * like an absent one: no resumable run of mine here, so another run's claim
    * on this branch still stands.
    *
    * Reading foreign logs before the stash can pick up uncommitted content,
    * which is fine here: this check only ever refuses.
    */
  private def abortIfBranchBusy(
      peeked: PeekedLog,
      ownPath: os.Path,
      workDir: os.Path,
      startingBranch: BranchName
  ): Unit =
    peeked match
      case PeekedLog.Parseable(_) => ()
      case _ =>
        busyBranchLog(ownPath, workDir, startingBranch).foreach: log =>
          throw new OrcaFlowException(
            branchBusyMessage(log, workDir, startingBranch)
          )

  /** The newest by mtime of the OTHER progress logs naming `startingBranch` —
    * newest-wins like the shell's resume offer, since several logs (different
    * prompts) can name one branch. Corrupt logs are already dropped by the
    * scan; a scan failure (`.orca` unreadable, or removed mid-listing) yields
    * `None` rather than propagating, since this guard-rail must not fail a run
    * that is otherwise fine.
    */
  private def busyBranchLog(
      ownPath: os.Path,
      workDir: os.Path,
      startingBranch: BranchName
  ): Option[ScannedProgressLog] =
    try
      ProgressScan
        .progressLogs(workDir)
        .filter(l => l.path != ownPath && l.header.branch == startingBranch)
        .maxByOption(l => os.mtime(l.path))
    catch case NonFatal(_) => None

  /** Identifies the run being refused — its recorded prompt and flow, plus the
    * log's own path, which is both how the user reads the full prompt back and
    * what they delete to abandon the run — and lists every way out.
    *
    * The prompt is header content, committed and hand-editable, so it reaches
    * the terminal through [[TextUtil.onelinePreview]]: sanitized, and clipped
    * so a long prompt can't bury the guidance after it.
    *
    * The shell's menu row is mentioned as a possibility, not a promise, and
    * only when the header records a flow — the shell needs one to offer the row
    * at all, but applies further conditions of its own (`ResumeDetector`), so
    * the always-available re-run route is given either way. Abandoning is
    * spelled as a git removal: the log is committed on the branch, so a plain
    * `rm` is undone by the next run's auto-stash restore.
    */
  private def branchBusyMessage(
      log: ScannedProgressLog,
      workDir: os.Path,
      startingBranch: BranchName
  ): String =
    val header = log.header
    val prompt = TextUtil.onelinePreview(header.userPrompt, 60)
    val flow = header.flow
      .map(source => s", flow: ${source.display}")
      .getOrElse("")
    val logPath = log.path.relativeTo(workDir)
    val shellRoute =
      if header.flow.isDefined then
        ", which the orca shell may also offer as \"Resume interrupted run\""
      else ""
    s"branch '${startingBranch.value}' already has an unfinished orca run on it " +
      s"(prompt: $prompt$flow, log: $logPath) — resume it by re-running its flow " +
      s"with the identical prompt$shellRoute, abandon it by removing its " +
      s"log (git rm $logPath && git commit -m \"abandon orca run\"), or " +
      "switch to a different branch before starting a new run"

  /** ADR 0019 migration warning: a repo that gitignores `.orca/` keeps the
    * committed stack settings out of version control, so every run names the
    * likely `.orca/` line to remove. Skipped under a programmatic override
    * (which neither reads nor writes the file). Best-effort and never fails the
    * flow.
    */
  private def warnIfSettingsIgnored(
      git: RuntimeGit,
      stack: SettingsResolution,
      emit: OrcaEvent => Unit
  ): Unit =
    val fileGoverns = stack match
      case SettingsResolution.Overridden(_) => false
      case SettingsResolution.Resolved(_) |
          SettingsResolution.NeedsDiscovery(_) =>
        true
    if fileGoverns && git.isIgnored(OrcaDir.settingsSubPath) then
      emit(
        OrcaEvent.Step(
          "stack settings at .orca/settings.properties are gitignored — " +
            "remove the '.orca/' line from .gitignore so they can be " +
            "committed (scratch self-ignores under .orca/cache/)"
        )
      )

  /** `name` as a [[FeatureBranch]]; throws when it is protected. */
  private[runner] def requestedBranch(
      name: BranchName,
      protectedBranches: Set[String]
  ): FeatureBranch =
    // `resolveReused`, not `resolve`: the latter's slug shape would refuse
    // valid user names such as `feature/JIRA-123`.
    FeatureBranch.resolveReused(name, protectedBranches) match
      case Right(featureBranch) => featureBranch
      case Left(refused) =>
        throw new OrcaFlowException(
          s"--branch '${refused.name}' is a protected branch in this repo — " +
            "pick another --branch name"
        )

  private[runner] def requestedBranchExists(
      name: BranchName
  ): OrcaFlowException =
    new OrcaFlowException(
      s"branch '${name.value}' already exists — check it out and re-run " +
        "with --skip-branch to continue on it, or pick another --branch name"
    )
