package orca.runner

import scala.annotation.tailrec
import scala.io.StdIn

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
private[runner] case class DirtyTreeFacts(
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
private[runner] object DirtyTreePolicy:

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
    * `RunCli.readTask`) so tests decide without a terminal.
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

  /** The production `ask`: a numbered menu on stderr, one line from stdin.
    *
    * stderr, not stdout, so the menu never lands in a redirected transcript;
    * `StdIn` rather than any richer input, since the runner deliberately owns
    * no terminal UI of its own. `setup`'s gate requires BOTH streams to be a
    * terminal, so neither half of the exchange can end up invisible.
    *
    * This writes the terminal outside `TerminalOutput`'s prompt transaction,
    * which is otherwise the single owner of the cursor. What makes that safe is
    * WHEN it runs: setup precedes the first stage, and the status row is only
    * ever raised by a `StageStarted`, so nothing is pinned at the bottom and
    * the animator's `tick` is a no-op. Routing it through the transaction
    * instead would mean handing setup the run's `Interaction` — including every
    * embedder's non-terminal one. Residual: a `Step` still queued in the
    * renderer's mailbox can print into the middle of the menu, which costs a
    * re-read, not a wrong answer; and the read blocks uninterruptibly, so a
    * fork failing elsewhere in the run's scope waits for the answer.
    */
  def promptOnStderr(dirtyCount: Int): DirtyTreeChoice =
    Console.err.println(
      s"$dirtyCount uncommitted/untracked file(s) in the working tree:\n" +
        "  1) stash them and start from a clean tree (default)\n" +
        "  2) keep them in place for the flow to work on (if the flow fails " +
        "before its first commit, kept changes to tracked files are lost)\n" +
        "  3) abort"
    )
    readAnswer()

  /** One `choice [1]:` line + read, repeated until [[parse]] accepts the answer
    * — an unrecognized one must not silently pick the stash default against the
    * user's intent. EOF and an empty line are accepted (as the default), so a
    * closed stdin cannot loop.
    */
  @tailrec
  private def readAnswer(): DirtyTreeChoice =
    Console.err.print("choice [1]: ")
    Console.err.flush()
    parse(Option(StdIn.readLine())) match
      case Some(choice) => choice
      case None =>
        Console.err.println("unrecognized answer — enter 1, 2, or 3")
        readAnswer()

  /** One menu answer: "1"/"2"/"3" name their entries; an empty line and EOF
    * (`readLine` yields `null` when stdin closes mid-prompt) mean the stash
    * default, matching what a headless run does. Any other answer is `None` —
    * unrecognized, for [[readAnswer]] to re-ask.
    */
  private[runner] def parse(answer: Option[String]): Option[DirtyTreeChoice] =
    answer.map(_.trim) match
      case None | Some("") | Some("1") => Some(DirtyTreeChoice.Stash)
      case Some("2")                   => Some(DirtyTreeChoice.Keep)
      case Some("3")                   => Some(DirtyTreeChoice.Abort)
      case Some(_)                     => None
