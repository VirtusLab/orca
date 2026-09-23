package orca.testkit

import orca.gitref.{BranchName, Head}
import orca.tools.GitTool

/** Test shorthand for a literal branch name the test knows is valid. */
def branchName(raw: String): BranchName =
  BranchName
    .parse(raw)
    .fold(e => throw new IllegalArgumentException(e), identity)

extension (git: GitTool)
  /** The name of the branch HEAD is on; throws when HEAD is detached. */
  def currentBranch(): String = git.head() match
    case Head.OnBranch(name) => name.value
    case detached            => throw new AssertionError(detached.describe)
