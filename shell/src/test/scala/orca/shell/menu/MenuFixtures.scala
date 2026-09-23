package orca.shell.menu

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.discovery.Origin
import orca.progress.FlowSource
import orca.shell.flows.DiscoveredFlow
import orca.shell.ui.{Choice, ShellUi, UiOutcome}

/** Answers a single fixed `confirm` outcome, recording the question it was
  * asked and the default offered; every other prompt is unsupported —
  * [[SettingsMenu.rediscoverStack]] only ever calls `confirm`.
  */
private[menu] class ConfirmOnlyUi(outcome: UiOutcome[Boolean]) extends ShellUi:
  var recordedQuestion: Option[String] = None
  var recordedDefault: Option[Boolean] = None
  protected def selectInOrder[A](
      title: String,
      choices: List[Choice[A]]
  ): UiOutcome[A] =
    throw new UnsupportedOperationException("rediscoverStack doesn't select")
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    recordedQuestion = Some(question)
    recordedDefault = Some(default)
    outcome
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("rediscoverStack doesn't input")
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("rediscoverStack doesn't input")

/** Records every `select` call's shown choices (in shown order) and always
  * answers with the fixed `outcome` — used to verify what
  * [[FlowPicker.pickFlow]] and [[RunMenu.promptRunTarget]] show, first row
  * included. `confirm`/`input` are unsupported: neither caller uses them.
  */
private[menu] class RecordingSelectUi[T](outcome: UiOutcome[T]) extends ShellUi:
  private var shown: List[List[Choice[T]]] = Nil
  def recordedChoices: List[List[Choice[T]]] = shown
  protected def selectInOrder[A](
      title: String,
      choices: List[Choice[A]]
  ): UiOutcome[A] =
    shown = shown :+ choices.asInstanceOf[List[Choice[T]]]
    outcome.asInstanceOf[UiOutcome[A]]
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    throw new UnsupportedOperationException("neither caller confirms")
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("neither caller inputs")
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("neither caller inputs")

/** Counts calls to `select`/`inputMultiline`/`input` and replays queued
  * outcomes for each — used to verify [[AuthoringMenu]] stop asking anything
  * the moment a prompt is cancelled, in particular that no further
  * harness/model/yolo prompt follows (authoring no longer has one). `confirm`
  * is unsupported: neither method calls it.
  */
private[menu] class FlowScriptedUi(
    selectScript: List[UiOutcome[Any]] = Nil,
    inputMultilineScript: List[UiOutcome[String]] = Nil,
    inputScript: List[UiOutcome[String]] = Nil,
    confirmScript: List[UiOutcome[Boolean]] = Nil
) extends ShellUi:
  private var pendingSelect = selectScript
  private var pendingInputMultiline = inputMultilineScript
  private var pendingInput = inputScript
  private var pendingConfirm = confirmScript
  var selectCount = 0
  var inputMultilineCount = 0
  var inputCount = 0

  protected def selectInOrder[A](
      title: String,
      choices: List[Choice[A]]
  ): UiOutcome[A] =
    selectCount += 1
    val outcome = pendingSelect.head
    pendingSelect = pendingSelect.tail
    outcome.asInstanceOf[UiOutcome[A]]

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    if pendingConfirm.isEmpty then
      throw new UnsupportedOperationException(
        "createNewFlow/createForkFlow don't confirm"
      )
    val outcome = pendingConfirm.head
    pendingConfirm = pendingConfirm.tail
    outcome

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    inputCount += 1
    val outcome = pendingInput.head
    pendingInput = pendingInput.tail
    outcome

  def inputMultiline(prompt: String): UiOutcome[String] =
    inputMultilineCount += 1
    val outcome = pendingInputMultiline.head
    pendingInputMultiline = pendingInputMultiline.tail
    outcome

private[menu] object MenuFixtures:

  def withDumbTerminal(body: Terminal => Unit): Unit =
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try body(terminal)
    finally terminal.close()

  def captured(body: => Unit): String =
    val buffer = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(buffer))(body)
    buffer.toString

  /** A [[SpawnEditor]] for tests that must never reach the editor. */
  val noEditor: SpawnEditor = (_, _) => throw AssertionError("editor spawned")

  def flow(name: String): DiscoveredFlow =
    DiscoveredFlow(
      name = name,
      description = None,
      origin = Origin.BuiltIn,
      path = os.root / s"$name",
      shadows = Nil,
      source = FlowSource.Catalog(name)
    )

  /** [[flow]] with an explicit origin/path — for tests that need a non-built-in
    * origin, or a real on-disk path since the hand-mode paths (edit-in-place,
    * customize-into-tier, fork-copy) actually read/copy the file.
    */
  def flowAt(
      name: String,
      origin: Origin,
      path: os.Path
  ): DiscoveredFlow =
    DiscoveredFlow(
      name = name,
      description = None,
      origin = origin,
      path = path,
      shadows = Nil,
      source = FlowSource.Catalog(name)
    )
