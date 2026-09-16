package orca.progress

import orca.tools.GitTool

/** The one rule for a branch that carries nothing but orca's bookkeeping. A
  * reused branch (`--skip-branch`) is never throwaway — orca did not create
  * it, and it IS the starting branch, so there is nothing to measure against.
  *
  * The lifecycle's teardown delete and the PR helpers' "did this run change
  * code" both read it, so the two cannot disagree.
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
