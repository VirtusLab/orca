package orca

import mainargs.{Flag, ParserForClass, arg}
import orca.progress.BranchName

/** The argv shape mainargs parses: one raw flag per `--`-spelled option,
  * including the `--worktree` combinations orca refuses. [[OrcaArgs.parse]] is
  * its only consumer, and turns the three run-destination flags into a
  * [[RunTarget]] — so nothing beyond the parse boundary holds them separately.
  */
private[orca] case class RawArgs(
    @arg(positional = true, doc = "task description")
    userPrompt: String = "",
    @arg(doc = "print a stack trace if the flow aborts")
    verbose: Flag = Flag(),
    @arg(doc = "run on the current branch instead of creating a new one")
    skipBranch: Flag = Flag(),
    @arg(doc =
      "keep uncommitted/untracked files in the working tree instead of stashing them (fresh runs only)"
    )
    keepChanges: Flag = Flag(),
    @arg(doc =
      "run the flow in a git worktree of this repository instead of the current checkout"
    )
    worktree: Flag = Flag(),
    @arg(doc =
      "name of the branch to create for this run (default: derived from the task); not with --skip-branch"
    )
    branch: Option[String] = None
)

/** Parsed command-line arguments for the `orca` entry point. */
case class OrcaArgs(
    userPrompt: String = "",
    verbose: Boolean = false,
    target: RunTarget = RunTarget.NewBranch(Uncommitted.Stash),
    branch: Option[BranchName] = None
)

object OrcaArgs:
  private given ParserForClass[RawArgs] = ParserForClass[RawArgs]

  /** Parse the given argv or return a human-readable error — including for a
    * contradictory flag pair or an invalid `--branch`, refused here so it fails
    * at parse, before the banner and before anything touches git.
    */
  def parse(args: Seq[String]): Either[String, OrcaArgs] =
    for
      raw <- summon[ParserForClass[RawArgs]].constructEither(args.toList)
      branch <- raw.branch match
        case Some(name) => BranchName.parse(name).map(Some(_))
        case None       => Right(None)
      target <- RunTarget.from(
        worktree = raw.worktree.value,
        skipBranch = raw.skipBranch.value,
        keepChanges = raw.keepChanges.value,
        branch = branch
      )
    yield OrcaArgs(raw.userPrompt, raw.verbose.value, target, branch)

  /** Overload for scala-cli flow scripts, whose top-level `args` is
    * `Array[String]`. Throws `OrcaFlowException` on a parse failure.
    */
  def apply(args: Array[String]): OrcaArgs = from(args.toSeq)

  def from(args: Seq[String]): OrcaArgs =
    parse(args) match
      case Right(parsed)  => parsed
      case Left(errorMsg) => throw OrcaFlowException(errorMsg)
