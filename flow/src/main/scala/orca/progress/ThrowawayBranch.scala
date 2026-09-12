package orca.progress

import orca.tools.GitTool

/** The one rule for a branch that carries nothing but orca's bookkeeping: orca
  * created it for this run, it is not the branch the run started on, and it is
  * diff-blank against that branch outside `.orca/`. A reused branch
  * (`--skip-branch`) is never throwaway — orca did not create it, and it IS the
  * starting branch, so there is nothing to measure it against.
  *
  * Read by the lifecycle to decide the teardown delete and by the PR helpers to
  * decide whether the run has anything to open a PR for, so the two cannot
  * disagree.
  */
object ThrowawayBranch:
  def isThrowaway(
      git: GitTool,
      branchMode: BranchMode,
      startBranch: String,
      featureBranch: String
  ): Boolean =
    branchMode == BranchMode.Created &&
      featureBranch != startBranch &&
      !git.branchHasChangesExcludingOrca(startBranch, featureBranch)
