package orca.progress

import orca.gitref.{BranchName, CommitHash}
import orca.tools.GitTool

/** The one rule for a branch that carries nothing but orca's bookkeeping. A
  * reused branch (`--skip-branch`) is never throwaway — orca did not create it.
  *
  * Measured against the commit the run started from rather than the branch it
  * started on: that branch may have moved during the run, and a detached start
  * has none.
  *
  * The lifecycle's teardown delete and the PR helpers' "did this run change
  * code" both read it, so the two cannot disagree.
  */
object ThrowawayBranch:
  def isThrowaway(
      git: GitTool,
      branchMode: BranchMode,
      startingCommit: CommitHash,
      featureBranch: BranchName
  ): Boolean =
    branchMode == BranchMode.Created &&
      !git.branchHasChangesExcludingOrca(startingCommit, featureBranch)
