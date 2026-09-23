package orca

/** The key of a run — one prompt's flow execution across every process that
  * resumes it: the first 12 hex chars of SHA-256(prompt). Names the run's
  * progress log, session records and worktree (`OrcaDir`), so re-running the
  * same prompt in the same repository lands on the same files.
  */
opaque type RunKey = String

object RunKey:
  /** The key of the run for `userPrompt`. */
  def of(userPrompt: String): RunKey =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(userPrompt.getBytes("UTF-8"))
    digest.iterator.take(6).map(b => f"${b & 0xff}%02x").mkString

  extension (k: RunKey)
    /** The string form, for file and directory names. */
    def value: String = k
