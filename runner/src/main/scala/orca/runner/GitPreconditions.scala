package orca.runner

/** What every entry point needs of git before a run can start, worded once.
  *
  * Its own home rather than [[FlowLifecycle]]'s: [[WorktreeRun]] checks the
  * same precondition at startup, before any lifecycle or `GitTool` exists, so
  * hanging the wording off the later and much larger of the two would point the
  * dependency backwards from the order things run in.
  */
private[runner] object GitPreconditions:

  /** Covers both "not a git repository" and "a repository with no commits" —
    * the same next step fixes either, and a caller can rarely tell them apart
    * without asking git a second question.
    */
  val needsRepoWithCommit: String =
    "orca needs a git repository with at least one commit — " +
      "initialize one if needed (git init), then make the first commit " +
      "(git add -A && git commit -m \"initial commit\")"
