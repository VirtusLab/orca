package orca

import mainargs.{Flag, ParserForClass, arg}
import orca.gitref.BranchName
import orca.progress.FeatureBranch

/** The argv shape mainargs parses, shared by a flow's own argv
  * ([[OrcaArgs.parse]]) and `orca run`, so each option is declared once. It
  * holds one raw value per option, including the combinations orca refuses;
  * [[checked]] refuses those.
  */
private[orca] case class RawArgs(
    @arg(positional = true, name = "prompt", doc = "what the flow should do")
    positionalPrompt: Option[String],
    @arg(name = "prompt", doc = "the prompt, for text starting with '-'")
    promptFlag: Option[String],
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
      "name of the branch to create for this run (default: derived from the prompt); not with --skip-branch"
    )
    branch: Option[String]
):
  /** Refuses a prompt given both positionally and with `--prompt`, an invalid
    * `--branch`, and a refused flag pair.
    */
  def checked: Either[String, CheckedArgs] =
    for
      givenPrompt <- (positionalPrompt, promptFlag) match
        case (Some(_), Some(_)) =>
          Left(
            "the prompt was given twice: pass it either as an argument or with " +
              "--prompt, not both"
          )
        case (Some(text), None) => Right(Some(text))
        case (None, text)       => Right(text)
      branchName <- FeatureBranch.parseRequestedOptional(branch)
      target <- RunTarget.from(
        worktree = worktree.value,
        skipBranch = skipBranch.value,
        keepChanges = keepChanges.value,
        branch = branchName
      )
    yield CheckedArgs(givenPrompt, verbose.value, target, branchName)

private[orca] object RawArgs:
  given ParserForClass[RawArgs] = ParserForClass[RawArgs]

/** [[RawArgs]] once checked. `givenPrompt` is `None` when argv has no prompt,
  * so `orca run` can refuse bad flags before it reads a piped one.
  */
private[orca] case class CheckedArgs(
    givenPrompt: Option[String],
    verbose: Boolean,
    target: RunTarget,
    branch: Option[BranchName]
):
  def withPrompt(userPrompt: String): OrcaArgs =
    OrcaArgs(
      userPrompt = userPrompt,
      verbose = verbose,
      target = target,
      branch = branch
    )
