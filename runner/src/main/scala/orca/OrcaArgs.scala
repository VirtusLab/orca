package orca

import mainargs.{Flag, ParserForClass, arg}
import orca.progress.BranchName

/** The argv shape mainargs parses, shared by a flow's own argv
  * ([[OrcaArgs.parse]]) and `orca run`, so each option is declared once. It
  * holds one raw value per option, including the combinations orca refuses;
  * [[checked]] turns them into an [[OrcaArgs]].
  */
private[orca] case class RawArgs(
    @arg(positional = true, doc = "task description")
    task: Option[String],
    @arg(doc = "task description, for text starting with '-'")
    prompt: Option[String],
    @arg(doc = "print a stack trace if the flow aborts")
    verbose: Flag,
    @arg(doc = "run on the current branch instead of creating a new one")
    skipBranch: Flag,
    @arg(doc =
      "keep uncommitted/untracked files in the working tree instead of stashing them (fresh runs only)"
    )
    keepChanges: Flag,
    @arg(doc =
      "run the flow in a git worktree of this repository instead of the current checkout"
    )
    worktree: Flag,
    @arg(doc =
      "name of the branch to create for this run (default: derived from the task); not with --skip-branch"
    )
    branch: Option[String]
):
  /** The task from the positional or `--prompt`, `None` when neither is given.
    */
  def taskText: Either[String, Option[String]] =
    (task, prompt) match
      case (Some(_), Some(_)) =>
        Left(
          "the task was given twice: pass it either as an argument or with " +
            "--prompt, not both"
        )
      case _ => Right(task.orElse(prompt))

  /** Every option but the task, checked: an invalid `--branch` or a refused
    * flag pair is a `Left`. The task is applied separately, so `orca run` can
    * refuse bad flags before it reads a piped task.
    */
  def checked: Either[String, String => OrcaArgs] =
    for
      branchName <- BranchName.parseOptional(branch)
      target <- RunTarget.from(
        worktree = worktree.value,
        skipBranch = skipBranch.value,
        keepChanges = keepChanges.value,
        branch = branchName
      )
    yield userPrompt =>
      OrcaArgs(
        userPrompt = userPrompt,
        verbose = verbose.value,
        target = target,
        branch = branchName
      )

private[orca] object RawArgs:
  given ParserForClass[RawArgs] = ParserForClass[RawArgs]

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
      if userPrompt.isEmpty then Nil
      else if userPrompt.startsWith("-") then Seq(s"--prompt=$userPrompt")
      else Seq(userPrompt)
    val targetArgv = target match
      case RunTarget.NewBranch(Uncommitted.Stash)     => Nil
      case RunTarget.NewBranch(Uncommitted.Keep)      => Seq("--keep-changes")
      case RunTarget.CurrentBranch(Uncommitted.Stash) => Seq("--skip-branch")
      case RunTarget.CurrentBranch(Uncommitted.Keep) =>
        Seq("--skip-branch", "--keep-changes")
      case RunTarget.Worktree => Seq("--worktree")
    taskArgv ++
      (if verbose then Seq("--verbose") else Nil) ++
      targetArgv ++
      branch.toList.flatMap(name => Seq("--branch", name.value))

object OrcaArgs:

  /** Parse the given argv or return a human-readable error — including for a
    * contradictory flag pair or an invalid `--branch`, refused here so it fails
    * at parse, before the banner and before anything touches git.
    */
  def parse(args: Seq[String]): Either[String, OrcaArgs] =
    for
      raw <- summon[ParserForClass[RawArgs]].constructEither(args.toList)
      task <- raw.taskText
      withTask <- raw.checked
    yield withTask(task.getOrElse(""))

  /** Overload for scala-cli flow scripts, whose top-level `args` is
    * `Array[String]`. Throws `OrcaFlowException` on a parse failure.
    */
  def apply(args: Array[String]): OrcaArgs = from(args.toSeq)

  def from(args: Seq[String]): OrcaArgs =
    parse(args) match
      case Right(parsed)  => parsed
      case Left(errorMsg) => throw OrcaFlowException(errorMsg)
