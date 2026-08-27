package orca

import mainargs.{Flag, ParserForClass, arg}

/** Parsed command-line arguments for the `orca` entry point. */
case class OrcaArgs(
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
    worktree: Flag = Flag()
)

object OrcaArgs:
  given ParserForClass[OrcaArgs] = ParserForClass[OrcaArgs]

  private val worktreeWithSkipBranchRefusal: String =
    "--worktree cannot be combined with --skip-branch: --skip-branch runs on " +
      "the branch checked out now, and git will not check that branch out a " +
      "second time in a new worktree"

  private val worktreeWithKeepChangesRefusal: String =
    "--worktree cannot be combined with --keep-changes: --keep-changes works " +
      "on uncommitted files, which stay behind in the invoking checkout — a " +
      "worktree is created from a commit and starts clean"

  /** Why this combination of flags cannot work, or `None` when it can.
    *
    * Over plain booleans so that every site holding the flags asks the one
    * question rather than restating it: the shell refuses the pair before it
    * spawns a flow at all, and an `OrcaArgs` written by hand in a flow script
    * never passed through [[parse]]. What must not drift is which pairs are
    * refused, not only how each refusal reads.
    */
  private[orca] def worktreeRefusal(
      worktree: Boolean,
      skipBranch: Boolean,
      keepChanges: Boolean
  ): Option[String] =
    if !worktree then None
    else if skipBranch then Some(worktreeWithSkipBranchRefusal)
    else if keepChanges then Some(worktreeWithKeepChangesRefusal)
    else None

  /** Parse the given argv or return a human-readable error — including for a
    * contradictory `--worktree` pair, refused here so it fails at parse, before
    * the banner and before anything touches git.
    */
  def parse(args: Seq[String]): Either[String, OrcaArgs] =
    summon[ParserForClass[OrcaArgs]]
      .constructEither(args.toList)
      .flatMap: parsed =>
        worktreeRefusal(
          worktree = parsed.worktree.value,
          skipBranch = parsed.skipBranch.value,
          keepChanges = parsed.keepChanges.value
        ).toLeft(parsed)

  /** Overload for scala-cli flow scripts, whose top-level `args` is
    * `Array[String]`. Throws `OrcaFlowException` on a parse failure.
    */
  def apply(args: Array[String]): OrcaArgs = from(args.toSeq)

  def from(args: Seq[String]): OrcaArgs =
    parse(args) match
      case Right(parsed)  => parsed
      case Left(errorMsg) => throw OrcaFlowException(errorMsg)
