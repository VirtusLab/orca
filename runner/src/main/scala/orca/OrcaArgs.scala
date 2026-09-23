package orca

import mainargs.ParserForClass
import orca.gitref.BranchName

/** Parsed command-line arguments for the `orca` entry point. */
case class OrcaArgs(
    userPrompt: String = "",
    verbose: Boolean = false,
    target: RunTarget = RunTarget.NewBranch(Uncommitted.Stash),
    branch: Option[BranchName] = None
):
  /** The argv [[OrcaArgs.parse]] reads back as this value; the shell passes it
    * to a flow child. A `branch` with a [[RunTarget.CurrentBranch]] target
    * renders an argv that `parse` refuses.
    *
    * The task is positional unless it starts with `-`, which mainargs reads as
    * a flag; then it is `--prompt=<text>`. A pin-honouring launch (ADR 0021 §2)
    * runs a flow built against an older orca, whose parser knows only the
    * positional.
    */
  def toArgv: Seq[String] =
    val taskArgv =
      if userPrompt.startsWith("-") then Seq(s"--prompt=$userPrompt")
      else Seq(userPrompt)
    val verboseArgv = if verbose then Seq("--verbose") else Nil
    val targetArgv = target match
      case RunTarget.NewBranch(Uncommitted.Stash)     => Nil
      case RunTarget.NewBranch(Uncommitted.Keep)      => Seq("--keep-changes")
      case RunTarget.CurrentBranch(Uncommitted.Stash) => Seq("--skip-branch")
      case RunTarget.CurrentBranch(Uncommitted.Keep) =>
        Seq("--skip-branch", "--keep-changes")
      case RunTarget.Worktree => Seq("--worktree")
    val branchArgv = branch.toList.flatMap(name => Seq("--branch", name.value))
    taskArgv ++ verboseArgv ++ targetArgv ++ branchArgv

object OrcaArgs:

  /** Parse the given argv or return a human-readable error — including for a
    * contradictory flag pair or an invalid `--branch`, refused here so it fails
    * at parse, before the banner and before anything touches git.
    */
  def parse(args: Seq[String]): Either[String, OrcaArgs] =
    for
      raw <- summon[ParserForClass[RawArgs]].constructEither(args.toList)
      checked <- raw.checked
    yield checked.withTask(checked.givenTask.getOrElse(""))

  /** Overload for scala-cli flow scripts, whose top-level `args` is
    * `Array[String]`. Throws `OrcaFlowException` on a parse failure.
    */
  def apply(args: Array[String]): OrcaArgs = from(args.toSeq)

  def from(args: Seq[String]): OrcaArgs =
    parse(args) match
      case Right(parsed)  => parsed
      case Left(errorMsg) => throw OrcaFlowException(errorMsg)
