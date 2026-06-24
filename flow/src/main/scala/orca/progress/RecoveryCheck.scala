package orca.progress

/** Validation of an untrusted [[ProgressHeader]] read back on resume (ADR 0018
  * §2.4/§2.5, R30/R32).
  *
  * The progress log is human-visible and pushable (R26), so its header is
  * untrusted input on load: it may have been hand-edited or carried onto the
  * wrong branch by a merge. Before any destructive git action (checkout, reset
  * --hard, delete) the runtime validates it here. A failure is a hard signal —
  * the caller aborts the run rather than silently proceeding or starting fresh.
  */
object RecoveryCheck:

  /** Whether `s` is a safe git ref for orca's purposes: non-empty, and every
    * `/`-separated segment matches the slug shape `^[a-z0-9][a-z0-9-]*$` (the
    * same charset [[orca.BranchNamingStrategy.slug]] produces). Issue branches
    * like `fix/issue-42` pass; ``, `-x`, `a/..`, `a b`, `Feat` are rejected.
    *
    * Forcing a leading alphanumeric blocks a name that begins with `-` (which
    * `git`/`gh` would read as a CLI flag) and bans path-traversal/whitespace
    * segments.
    */
  def isSafeBranchRef(s: String): Boolean =
    s.nonEmpty && s.split("/", -1).forall(isSafeSegment)

  private def isSafeSegment(seg: String): Boolean =
    seg.nonEmpty &&
      isSlugChar(seg.head) && seg.head != '-' &&
      seg.forall(isSlugChar)

  private def isSlugChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'

  /** Protected branches orca must never checkout/reset/delete as a *feature*
    * branch: `main`/`master`, case-insensitive. `startingBranch` may be one of
    * these (the run returns to it), but `header.branch` may not.
    */
  def isProtectedBranch(s: String): Boolean =
    val lower = s.toLowerCase
    lower == "main" || lower == "master"

  /** Validate the header before any destructive action. Returns `Left(reason)`
    * on the first failure, `Right(())` when the header is trustworthy.
    *
    *   - `branch` and `startingBranch` must be safe refs.
    *   - `branch` must not be a protected branch (`startingBranch` may be).
    *   - `promptHash` must equal the recomputed hash of the current prompt.
    */
  def validateHeader(
      header: ProgressHeader,
      userPrompt: String
  ): Either[String, Unit] =
    if !isSafeBranchRef(header.branch) then
      Left(s"branch '${header.branch}' is not a safe ref")
    else if !isSafeBranchRef(header.startingBranch) then
      Left(s"startingBranch '${header.startingBranch}' is not a safe ref")
    else if isProtectedBranch(header.branch) then
      Left(s"branch '${header.branch}' is a protected branch")
    else if header.promptHash != ProgressStore.hashPrompt(userPrompt) then
      Left("promptHash does not match the current prompt")
    else Right(())
