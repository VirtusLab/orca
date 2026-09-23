package orca.gitref

/** What HEAD points at — see `GitTool.head`. */
enum Head:
  case OnBranch(name: BranchName)

  /** Detached (a CI checkout, a `git checkout <sha>`), at commit `at`. */
  case Detached(at: CommitHash)

  /** HEAD as a person reads it: the branch name, or the detached commit. */
  def describe: String = this match
    case OnBranch(name) => s"branch '${name.value}'"
    case Detached(at)   => s"detached HEAD at ${at.short}"
