package orca.lifecycle

/** What setup does with a working tree holding uncommitted or untracked files
  * (ADR 0018 §2.5) — the outcome of [[DirtyTreePolicy.decide]].
  */
private[orca] enum DirtyTreeChoice:
  /** Stash everything, so the run starts from committed content. */
  case Stash

  /** Leave the files in place, for the flow to work on and commit. */
  case Keep

  /** Refuse to start, before anything in the tree is touched. */
  case Abort

/** The run facts [[DirtyTreePolicy.decide]] reads. Named rather than four bare
  * arguments in a row, so a transposed one cannot silently invert the decision.
  */
private[lifecycle] case class DirtyTreeFacts(
    // Presence, NOT resumability: a corrupt log is present but resumes nothing
    // (`bindBranch` warns and starts fresh from it). Both cases must still
    // start from a clean tree — the stash is the only thing that reverts a
    // broken in-progress edit of the log to its committed content.
    ownLogPresent: Boolean,
    skipBranch: Boolean,
    keepChanges: Boolean,
    dirtyCount: Int
)

/** The dirty-tree decision, separated from the git and event machinery that
  * carries it out so every cell of it is testable without a repository or a
  * terminal.
  */
private[orca] object DirtyTreePolicy:

  /** Which of [[DirtyTreeChoice]] applies, given the run's facts.
    *
    * Only one case is open enough to ask the user about: a FRESH run, with a
    * dirty tree, that named neither `--keep-changes` nor `--skip-branch` (both
    * already say "keep"). Everything else is settled by the facts alone — which
    * is what keeps an unattended run unattended.
    *
    * `tty` and `ask` (handed `facts.dirtyCount`, all the menu shows) are called
    * only in that one case: the probe spawns a subprocess, and it is worth
    * nothing in the settled cases, where an off-tty run must also reach the
    * stash default without reading stdin. Both are injected (as in
    * `RunCli.readPrompt`) so tests decide without a terminal.
    */
  def decide(
      facts: DirtyTreeFacts,
      tty: () => Boolean,
      ask: Int => DirtyTreeChoice
  ): DirtyTreeChoice =
    if facts.ownLogPresent then DirtyTreeChoice.Stash
    else if facts.skipBranch || facts.keepChanges then DirtyTreeChoice.Keep
    else if facts.dirtyCount == 0 then DirtyTreeChoice.Stash
    else if tty() then ask(facts.dirtyCount)
    else DirtyTreeChoice.Stash

  /** One menu answer: "1"/"2"/"3" name their entries; an empty line and EOF
    * (`readLine` yields `null` when stdin closes mid-prompt) mean the stash
    * default, matching what a headless run does. Any other answer is `None` —
    * unrecognized, for `DirtyTreePrompt` to re-ask.
    */
  private[orca] def parse(
      answer: Option[String]
  ): Option[DirtyTreeChoice] =
    answer.map(_.trim) match
      case None | Some("") | Some("1") => Some(DirtyTreeChoice.Stash)
      case Some("2")                   => Some(DirtyTreeChoice.Keep)
      case Some("3")                   => Some(DirtyTreeChoice.Abort)
      case Some(_)                     => None
