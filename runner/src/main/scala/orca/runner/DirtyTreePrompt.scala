package orca.runner

import orca.lifecycle.{DirtyTreeChoice, DirtyTreePolicy}
import scala.annotation.tailrec
import scala.io.StdIn

/** The terminal half of [[DirtyTreePolicy]]. */
private[orca] object DirtyTreePrompt:

  /** The production `ask`: a numbered menu on stderr, one line from stdin.
    *
    * stderr, not stdout, so the menu never lands in a redirected transcript;
    * `StdIn` rather than any richer input, since the runner deliberately owns
    * no terminal UI of its own. `setup`'s gate requires BOTH streams to be a
    * terminal, so neither half of the exchange can end up invisible.
    *
    * This writes the terminal outside `TerminalOutput`'s prompt transaction,
    * which is otherwise the single owner of the cursor. What makes that safe is
    * WHEN it runs: before any stage or setup activity raises the status row, so
    * nothing is pinned at the bottom and the animator's `tick` is a no-op.
    * Routing it through the transaction instead would mean handing setup the
    * run's `Interaction` — including every embedder's non-terminal one.
    * Residual: the read blocks uninterruptibly, so a fork failing elsewhere in
    * the run's scope waits for the answer.
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

  /** One `choice [1]:` line + read, repeated until [[DirtyTreePolicy.parse]]
    * accepts the answer — an unrecognized one must not silently pick the stash
    * default against the user's intent. EOF and an empty line are accepted (as
    * the default), so a closed stdin cannot loop.
    */
  @tailrec
  private def readAnswer(): DirtyTreeChoice =
    Console.err.print("choice [1]: ")
    Console.err.flush()
    DirtyTreePolicy.parse(Option(StdIn.readLine())) match
      case Some(choice) => choice
      case None         =>
        Console.err.println("unrecognized answer — enter 1, 2, or 3")
        readAnswer()
