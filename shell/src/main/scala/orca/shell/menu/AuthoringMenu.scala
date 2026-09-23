package orca.shell.menu

import org.jline.terminal.Terminal
import orca.discovery.Origin
import orca.shell.{OrcaBuild, ShellEnv, Tier}
import orca.shell.actions.AuthorAction
import orca.shell.create.{FlowAuthoring, FlowDestination}
import orca.shell.flows.{DiscoveredFlow, FlowEditor}
import orca.shell.run.FlowLauncher
import orca.shell.ui.{Choice, ShellOutput, ShellUi, UiOutcome}
import ox.discard

import scala.annotation.tailrec

/** How Edit/Create/Fork make their changes (ADR 0021 §6/§9 amendment): asked
  * via [[AuthoringMenu.modeChoices]] after the action's WHAT is established
  * (which flow to edit; source+tier to fork; nothing yet for create, where the
  * mode decides whether a goal or a filename comes next).
  */
private[menu] enum ChangeMode:
  case Hand, Agent

/** The menu's Edit, Create and Fork items (ADR 0021 §6/§9): each asks how the
  * changes are made — by hand in `spawnEditor` (`EditAction.editInPlace` in
  * production), or by an agent through [[AuthorAction]].
  */
private[menu] object AuthoringMenu:

  /** "How should the changes be made?" — the two-row hand-vs-agent prompt
    * shared by Edit/Create/Fork (ADR 0021 §6/§9 amendment).
    */
  val modeChoices: List[Choice[ChangeMode]] = List(
    Choice(
      ChangeMode.Agent,
      "With an agent — describe the changes and let it work"
    ),
    Choice(ChangeMode.Hand, "By hand — open in your editor")
  )

  /** Edit-a-flow: pick the flow, then the mode, then where the edit lands
    * ([[editDestination]]).
    */
  def editFlow(
      ui: ShellUi,
      terminal: Terminal,
      spawnEditor: SpawnEditor
  )(using ShellEnv): Unit =
    for
      flow <- FlowPicker.selectFlow(ui, "Edit which flow?")
      mode <- pickChangeMode(ui)
      destination <- editDestination(ui, flow)
    do
      mode match
        case ChangeMode.Hand =>
          spawnEditor(terminal, destination.flowPath).discard
        case ChangeMode.Agent =>
          editByAgent(ui, terminal, flow, destination)

  /** Where an edit writes: the flow itself, or — for a built-in, which is never
    * edited in its cache copy — a copy customized into a picked tier
    * ([[FlowEditor.customizeTarget]]). `None` when the tier prompt is cancelled
    * or the copy is refused.
    */
  private[menu] def editDestination(ui: ShellUi, flow: DiscoveredFlow)(using
      ShellEnv
  ): Option[FlowDestination] =
    flow.origin match
      case Origin.Project => Some(FlowDestination.of(Tier.Project, flow.path))
      case Origin.Global  => Some(FlowDestination.of(Tier.Global, flow.path))
      case Origin.BuiltIn =>
        val title = s"'${flow.name}' is built-in — customize it into:"
        pickTier(ui, title).flatMap: tier =>
          FlowEditor.customizeTarget(flow, tier) match
            case Left(message) =>
              ShellOutput.error(message)
              None
            case Right(path) => Some(FlowDestination.of(tier, path))

  /** Prompts for the changes and runs the agent edit, which overwrites
    * `destination` — the flow itself, or its customized copy.
    */
  private def editByAgent(
      ui: ShellUi,
      terminal: Terminal,
      flow: DiscoveredFlow,
      destination: FlowDestination
  )(using ShellEnv): Unit =
    promptDescription(ui, "Describe the changes for the edit").foreach:
      changes =>
        AuthorAction
          .edit(
            flow,
            changes,
            destination,
            ui,
            terminal,
            FlowLauncher.runAnnounced
          )
          .discard

  /** New-flow authoring: mode FIRST — it decides whether a goal (agent) or a
    * filename (hand) comes next.
    */
  def createNewFlow(
      ui: ShellUi,
      terminal: Terminal,
      spawnEditor: SpawnEditor
  )(using ShellEnv): Unit =
    pickChangeMode(ui).foreach:
      case ChangeMode.Hand  => createNewFlowByHand(ui, terminal, spawnEditor)
      case ChangeMode.Agent => createNewFlowByAgent(ui, terminal)

  /** Create+hand (ADR 0021 §9 amendment): tier, then a filename (there's no
    * goal to slug a default from), then a minimal compiling
    * [[FlowAuthoring.skeletonFlow]] is written and opened in the editor.
    */
  private def createNewFlowByHand(
      ui: ShellUi,
      terminal: Terminal,
      spawnEditor: SpawnEditor
  )(using ShellEnv): Unit =
    for
      tier <- pickTier(ui, "Where should the new flow be saved:")
      target <- promptNewFlowFilename(ui, tier)
    do
      os.write.over(
        target.flowPath,
        FlowAuthoring.skeletonFlow(OrcaBuild.current)
      )
      spawnEditor(terminal, target.flowPath).discard

  /** Prompts for the new flow's filename, defaulted to `new-flow.sc` and
    * validated/collision-refused the same way the CLI's explicit `name`
    * argument is ([[FlowAuthoring.validateFileName]] +
    * [[FlowAuthoring.safePrepareTarget]]) — re-prompting on either problem
    * rather than aborting, since a taken or invalid name is easy to fix without
    * starting the whole flow over.
    */
  @tailrec private def promptNewFlowFilename(ui: ShellUi, tier: Tier)(using
      ShellEnv
  ): Option[FlowDestination] =
    ui.input("Filename for the new flow", default = Some("new-flow.sc")) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(name) =>
        val prepared =
          for
            _ <- FlowAuthoring.validateFileName(name)
            target <- FlowAuthoring.safePrepareTarget(tier, name)
          yield target
        prepared match
          case Left(message) =>
            ShellOutput.error(message)
            promptNewFlowFilename(ui, tier)
          case Right(target) => Some(target)

  /** Create+agent: tier → goal, filename auto-derived from the goal's
    * [[FlowAuthoring.suggestFilenameForGoal]] slug (uniquified on collision —
    * never prompted for), then hands off to [[AuthorAction.create]], which runs
    * the built-in `simple.sc` flow in a throwaway
    * [[orca.shell.create.AuthoringSandbox]]. Cancelling any prompt aborts back
    * to the menu without launching anything.
    */
  private def createNewFlowByAgent(ui: ShellUi, terminal: Terminal)(using
      ShellEnv
  ): Unit =
    for
      tier <- pickTier(ui, "Where should the new flow be saved:")
      goal <- promptDescription(ui, "Describe what the flow should do")
    do
      ShellOutput.info("picking a filename…")
      val target = FlowAuthoring.prepareAutoTarget(
        tier,
        FlowAuthoring.suggestFilenameForGoal(goal)
      )
      ShellOutput.info(s"filename: ${target.flowPath.last}")
      AuthorAction
        .create(goal, target, ui, terminal, FlowLauncher.runAnnounced)
        .discard

  /** Fork-an-existing-flow: pick the source flow from every tier (same rows
    * View/Edit use) → tier for the fork's target → mode → hand or agent.
    */
  def createForkFlow(
      ui: ShellUi,
      terminal: Terminal,
      spawnEditor: SpawnEditor
  )(using ShellEnv): Unit =
    for
      source <- FlowPicker.selectFlow(ui, "Fork which flow?")
      tier <- pickTier(ui, "Where should the fork be saved:")
      mode <- pickChangeMode(ui)
    do
      mode match
        case ChangeMode.Hand =>
          forkFlowByHand(terminal, source, tier, spawnEditor)
        case ChangeMode.Agent =>
          forkFlowByAgent(ui, terminal, source, tier)

  /** Fork+hand (ADR 0021 §9 amendment): copies the source straight to the
    * fork's auto-derived target ([[FlowAuthoring.forkFilenameDefault]]), then
    * opens the copy in the editor — no changes prompt, since there's no agent
    * to describe them to.
    */
  private def forkFlowByHand(
      terminal: Terminal,
      source: DiscoveredFlow,
      tier: Tier,
      spawnEditor: SpawnEditor
  )(using ShellEnv): Unit =
    val target = FlowAuthoring.prepareAutoTarget(
      tier,
      FlowAuthoring.forkFilenameDefault(source.name)
    )
    os.copy(source.path, target.flowPath, createFolders = true)
    spawnEditor(terminal, target.flowPath).discard

  /** Fork+agent: describe the changes, filename auto-derived via
    * [[FlowAuthoring.suggestFilenameForFork]] (uniquified on collision — never
    * prompted for), then hands off to [[AuthorAction.fork]].
    */
  private def forkFlowByAgent(
      ui: ShellUi,
      terminal: Terminal,
      source: DiscoveredFlow,
      tier: Tier
  )(using ShellEnv): Unit =
    promptDescription(ui, "Describe the changes for the fork").foreach:
      changes =>
        ShellOutput.info("picking a filename…")
        val target = FlowAuthoring.prepareAutoTarget(
          tier,
          FlowAuthoring.suggestFilenameForFork(
            source.name,
            source.description,
            changes
          )
        )
        ShellOutput.info(s"filename: ${target.flowPath.last}")
        AuthorAction
          .fork(
            source,
            changes,
            target,
            ui,
            terminal,
            FlowLauncher.runAnnounced
          )
          .discard

  /** "How should the changes be made?" — [[modeChoices]]. */
  private def pickChangeMode(ui: ShellUi): Option[ChangeMode] =
    ui.select(
      "How should the changes be made?",
      modeChoices,
      default = Some(ChangeMode.Agent)
    ).toOption

  /** The Project/Global flow-tier picker; only the `title` differs per use. */
  private def pickTier(ui: ShellUi, title: String)(using
      env: ShellEnv
  ): Option[Tier] =
    ui.select(
      title,
      List(
        Choice(Tier.Project, "Project (.orca/flows/)"),
        Choice(Tier.Global, s"Global (${env.configHome.flows})")
      )
    ).toOption

  private def promptDescription(ui: ShellUi, label: String): Option[String] =
    Prompts.nonBlankMultiline(ui, label, "description can't be empty")
