package orca.shell.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import mainargs.{Flag, ParserForMethods, Renderer, Util, arg, main}
import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec, GlobalSettings}
import orca.shell.actions.{
  AuthorAction,
  AuthorOutcome,
  AuthorParams,
  ConfigAction,
  EditAction,
  FlowResolution,
  RunAction,
  SessionAction,
  SessionSelection,
  StackAction,
  StackStatus,
  ViewAction
}
import orca.shell.create.{CreateFlow, CreateTarget, CreateTier}
import orca.shell.flows.{CustomizeTier, DiscoveredFlow, FlowOrigin}
import orca.shell.run.{
  ChildTerminal,
  FallbackPolicy,
  FlowLauncher,
  LaunchResult
}
import orca.shell.sessions.{ManifestReader, ReadRun, SessionPicker}
import orca.shell.ui.{Choice, ShellOutput, ShellUi, UiOutcome}

/** Exit codes shared by every subcommand (ADR 0021 §10/§4). */
private[shell] object ExitCodes:
  val Ok = 0
  val ActionFailed = 1
  val UsageError = 2
  val SignalKilled = 130

/** The non-interactive CLI's mainargs subcommand surface (ADR 0021 §10): one
  * `@main` method per verb, each returning the process exit code rather than
  * calling `sys.exit` itself — [[orca.shell.Main.main]] is the only place that
  * exits, once [[dispatch]] has returned. Every method here parses its own
  * argv-shaped parameters and hands off to the `actions/` layer the interactive
  * menu also calls, so behavior matches `Main`'s prompting flows exactly, minus
  * the prompts.
  */
private[shell] object Cli:

  private val nameMapper = Util.kebabCaseNameMapper

  /** Every CLI-path diagnostic (errors, `ManifestReader` warnings) goes here,
    * plain text with no ANSI/fansi coloring — unlike [[ShellOutput.error]],
    * which is stdout-and-colored for the interactive shell. `--json`/table data
    * is exclusively `println` (stdout); this is the only other output a CLI
    * subcommand ever produces, so routing it to stderr is what keeps e.g. `orca
    * continue --list --json | jq` parseable.
    */
  private def fail(msg: String): Unit = Console.err.println(msg)

  /** Every subcommand's dispatch name, kebab-mapped from its method name —
    * `Main.main`'s single source of truth for "is this token a subcommand".
    */
  def commandNames: Set[String] =
    ParserForMethods(Cli).mains.value.map(_.name(nameMapper)).toSet

  /** Runs `args` (the full argv, subcommand name included) and returns the
    * process exit code. `--help`/`-h` anywhere in the subcommand's own
    * arguments prints just that subcommand's mainargs-rendered signature and
    * returns 0, bypassing mainargs' own generic argument-error path (which
    * would otherwise report "--help" as an unknown argument). Everything else
    * goes through [[ParserForMethods.runEither]]; a `Left` (bad/missing args,
    * unresolvable subcommand) is a usage error, `Right` is the invoked method's
    * own returned exit code.
    */
  def dispatch(args: Seq[String]): Int =
    require(
      args.nonEmpty,
      "dispatch requires a non-empty argv — Main.main only calls this after " +
        "confirming args.head names a known subcommand"
    )
    val name = args.head
    if args.tail.exists(t => t == "--help" || t == "-h") then
      println(commandHelp(name).getOrElse(s"orca: unknown command '$name'"))
      ExitCodes.Ok
    else
      ParserForMethods(Cli).runEither(args, autoPrintHelpAndExit = None) match
        case Left(usage) =>
          Console.err.println(usage)
          ExitCodes.UsageError
        case Right(exitCode: Int) => exitCode
        case Right(_) =>
          ExitCodes.ActionFailed // unreachable: every @main below returns Int

  /** `orca <name> --help`'s text: `name`'s own mainargs-rendered signature and
    * arg docs, or `None` if `name` isn't a known subcommand.
    */
  private[cli] def commandHelp(name: String): Option[String] =
    ParserForMethods(Cli).mains.value
      .find(_.name(nameMapper) == name)
      .map: m =>
        val leftColWidth =
          Renderer.getLeftColWidth(m.renderedArgSigs, nameMapper)
        Renderer.formatMainMethodSignature(
          m,
          0,
          100,
          leftColWidth,
          docsOnNewLine = false,
          customName = None,
          customDoc = None,
          sorted = true,
          nameMapper = nameMapper
        )

  // --- run ---

  @main(
    doc = "Run a flow, propagating its exit code.\n" +
      "Task is read from stdin when omitted and stdin is piped.\n" +
      """Example: orca run implement.sc "add a rate limiter""""
  )
  def run(
      @arg(positional = true, doc = "flow name or path")
      flow: String,
      @arg(positional = true, doc = "task text (read from stdin when omitted)")
      task: Option[String] = None,
      @arg(doc = "pass the flow's own --verbose flag")
      verbose: Flag = Flag(),
      @arg(doc =
        "run the flow's own pinned orca version instead of forcing this shell's"
      )
      honorPin: Flag = Flag()
  ): Int =
    val workDir = os.pwd
    FlowResolution.resolve(flow, workDir) match
      case Left(message) =>
        fail(message)
        ExitCodes.ActionFailed
      case Right(resolved) =>
        readTask(task, isTty, readAllStdin) match
          case Left(message) =>
            fail(message)
            ExitCodes.UsageError
          case Right(taskText) =>
            val terminal = buildTerminal()
            try
              val result =
                if honorPin.value then
                  runHonoringPin(
                    resolved,
                    taskText,
                    verbose.value,
                    workDir,
                    terminal
                  )
                else
                  RunAction.run(
                    resolved,
                    taskText,
                    RunAction.RunOptions(
                      verbose = verbose.value,
                      fallback =
                        FallbackPolicy.Refuse("re-run with --honor-pin")
                    ),
                    workDir,
                    terminal
                  )
              // propagates the flow child's raw exit code (LaunchResult.Failed's
              // exit, via exitCodeFor) — run mirrors a wrapped subprocess's
              // status rather than the flat 0/1/2 usage-error convention.
              exitCodeFor(result)
            finally terminal.close()

  private def readAllStdin(): String =
    String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)

  /** `task` when non-blank; `readStdin()` read to EOF when `task` is omitted
    * and `tty` is false (`enables generate-prompt | orca run fix.sc`); a usage
    * error otherwise — never blocks waiting on a terminal that has no task
    * coming. `tty`/`readStdin` are injected (production: [[isTty]]/
    * [[readAllStdin]]) so tests exercise every branch without touching the real
    * console or blocking on real stdin.
    */
  private[cli] def readTask(
      task: Option[String],
      tty: Boolean,
      readStdin: () => String
  ): Either[String, String] =
    task match
      case Some(text) if text.trim.nonEmpty => Right(text.trim)
      case Some(_)                          => Left("task text can't be empty")
      case None =>
        if tty then
          Left(
            "no task given, and stdin is a terminal — " +
              "pass the task as an argument, or pipe it in"
          )
        else
          val piped = readStdin().trim
          if piped.isEmpty then Left("no task given, and stdin was empty")
          else Right(piped)

  /** `--honor-pin`'s direct pin-honouring run: [[FlowLauncher.run]] has no
    * "skip the forced version from the start" entry point — only the
    * interactive fallback offers a pin-honouring RE-run after a forced failure
    * — so this spawns [[FlowLauncher.argv]]'s pin-honouring argv itself, under
    * the same [[ChildTerminal.withChild]] bracket and `ORCA_FLOW_NAME` env
    * stamp `FlowLauncher.run` uses, reusing [[FlowLauncher.outcomeSuffix]] for
    * the end-of-run message.
    */
  private def runHonoringPin(
      flow: DiscoveredFlow,
      task: String,
      verbose: Boolean,
      workDir: os.Path,
      terminal: Terminal
  ): LaunchResult =
    val argv = FlowLauncher.argv(flow.path, None, task, verbose)
    println()
    ShellOutput.section(s"starting flow ${flow.name} (honoring pin)")
    val exit = ChildTerminal.withChild(terminal):
      os.proc(argv)
        .call(
          cwd = workDir,
          env = Map("ORCA_FLOW_NAME" -> flow.path.last),
          stdin = os.Inherit,
          stdout = os.Inherit,
          stderr = os.Inherit,
          check = false
        )
        .exitCode
    val result = FlowLauncher.toLaunchResult(exit)
    ShellOutput.section(
      s"flow ${flow.name} ${FlowLauncher.outcomeSuffix(result)}"
    )
    println()
    result

  private[cli] def exitCodeFor(result: LaunchResult): Int = result match
    case LaunchResult.Ok           => ExitCodes.Ok
    case LaunchResult.Failed(exit) => exit
    case LaunchResult.Cancelled    => ExitCodes.SignalKilled

  // --- view ---

  @main(doc =
    "Print a flow's source (highlighted only when stdout is a terminal; " +
      "--plain/--color override the auto-detection).\n" +
      "Example: orca view implement.sc"
  )
  def view(
      @arg(positional = true, doc = "flow name or path")
      flow: String,
      @arg(doc = "never highlight, regardless of stdout")
      plain: Flag = Flag(),
      @arg(doc = "always highlight, regardless of stdout")
      color: Flag = Flag()
  ): Int =
    resolveHighlight(plain.value, color.value, isTty) match
      case Left(message) =>
        fail(message)
        ExitCodes.UsageError
      case Right(highlight) => runView(os.pwd, flow, highlight)

  /** `--plain`/`--color`'s resolution (ADR 0021 §10 fold-in): mutually
    * exclusive; either wins outright over the auto-detected `tty` — JDK 21 has
    * no clean stdout-only tty probe, so an explicit flag is the escape hatch
    * for `view | less -R` (wants highlighting) or a genuinely-a-terminal stdout
    * piped through something that mangles ANSI (wants none). `tty` is injected
    * (production: [[isTty]]) so every branch is testable without a real
    * console.
    */
  private[cli] def resolveHighlight(
      plain: Boolean,
      color: Boolean,
      tty: Boolean
  ): Either[String, Boolean] =
    if plain && color then Left("--plain and --color are mutually exclusive")
    else if plain then Right(false)
    else if color then Right(true)
    else Right(tty)

  /** `view`'s full behavior over an explicit `workDir` and resolved `highlight`
    * decision — pulled out of the `@main` method so tests can point it at a
    * temp project and pass a resolved boolean directly, without touching the
    * real console.
    */
  private[cli] def runView(
      workDir: os.Path,
      flowRef: String,
      highlight: Boolean
  ): Int =
    FlowResolution.resolve(flowRef, workDir) match
      case Left(message) =>
        fail(message)
        ExitCodes.ActionFailed
      case Right(resolved) =>
        println(ViewAction.render(resolved, highlight))
        ExitCodes.Ok

  // --- edit ---

  @main(doc =
    "Open a flow in $VISUAL/$EDITOR/vi.\n" +
      "A built-in flow needs --to (it's copied into that tier first).\n" +
      "Example: orca edit implement.sc"
  )
  def edit(
      @arg(positional = true, doc = "flow name or path")
      flow: String,
      @arg(doc = "tier to customize a built-in flow into: project|global")
      to: Option[String] = None
  ): Int =
    requireTty("edit", isTty) match
      case Left(message) =>
        fail(message)
        ExitCodes.UsageError
      case Right(()) =>
        val workDir = os.pwd
        FlowResolution.resolve(flow, workDir) match
          case Left(message) =>
            fail(message)
            ExitCodes.ActionFailed
          case Right(resolved) =>
            val terminal = buildTerminal()
            try runEdit(resolved, to, workDir, terminal)
            finally terminal.close()

  private def runEdit(
      flow: DiscoveredFlow,
      to: Option[String],
      workDir: os.Path,
      terminal: Terminal
  ): Int =
    if flow.origin != FlowOrigin.BuiltIn then
      if to.isDefined then
        fail("--to only applies when customizing a built-in flow")
        ExitCodes.UsageError
      // propagates the editor child's raw exit code — same
      // wraps-a-subprocess convention as run/continue.
      else EditAction.editInPlace(terminal, flow.path)
    else
      to match
        case None =>
          fail(
            "'" + flow.name + "' is built-in — pass --to project|global to customize it"
          )
          ExitCodes.UsageError
        case Some(raw) =>
          parseCustomizeTier(raw) match
            case Left(message) =>
              fail(message)
              ExitCodes.UsageError
            case Right(tier) =>
              EditAction.customizeThenEdit(
                terminal,
                flow,
                tier,
                workDir,
                GlobalSettings.defaultFlows
              ) match
                case Left(message) =>
                  fail(message)
                  ExitCodes.ActionFailed
                case Right(exit) => exit

  private[cli] def parseCustomizeTier(
      raw: String
  ): Either[String, CustomizeTier] =
    raw match
      case "project" => Right(CustomizeTier.Project)
      case "global"  => Right(CustomizeTier.Global)
      case other => Left(s"--to must be 'project' or 'global', got '$other'")

  // --- create / fork ---

  /** `name`/fork's filename argument is documented as a bare filename, not a
    * path — rejects one containing a path separator (`../escape.sc`,
    * `sub/dir.sc`) with a clean usage error up front, before it ever reaches
    * [[CreateFlow.prepareTarget]]'s path arithmetic (which, for a name with
    * enough `..`s, os-lib can reject by throwing a raw `PathError` instead of
    * returning one).
    */
  private[cli] def validateFileName(fileName: String): Either[String, Unit] =
    Either.cond(
      !fileName.contains("/") && !fileName.contains("\\"),
      (),
      s"'$fileName' isn't a valid flow filename — path separators aren't allowed"
    )

  /** [[CreateFlow.prepareTarget]], with any exception os-lib's path arithmetic
    * throws for a filename that survived [[validateFileName]] but still drives
    * it outside the filesystem root (e.g.
    * `os.PathError.AbsolutePathOutsideRoot` — every `os.PathError` variant is a
    * plain `IllegalArgumentException`, there's no shared marker type to catch)
    * converted to a clean `Left` instead of propagating as an uncaught
    * exception.
    */
  private[cli] def safePrepareTarget(
      tier: CreateTier,
      fileName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, CreateTarget] =
    try CreateFlow.prepareTarget(tier, fileName, workDir, globalFlows)
    catch
      case _: IllegalArgumentException =>
        Left(s"'$fileName' isn't a valid flow filename")

  @main(doc =
    "Author a new flow with a coding agent's help. --goal is required; yolo defaults on.\n" +
      """Example: orca create --goal "summarize a PR's review threads" --harness claude"""
  )
  def create(
      @arg(
        positional = true,
        doc = "flow filename (default: suggested from --goal)"
      )
      name: Option[String] = None,
      @arg(doc = "what the flow should do")
      goal: String,
      @arg(doc =
        "harness to author with: claude|codex|pi|gemini|opencode (default: configured coding agent)"
      )
      harness: Option[String] = None,
      @arg(doc =
        "save under the global flows directory instead of the project's"
      )
      global: Flag = Flag(),
      @arg(doc = "let the harness run without approval prompts (default: on)")
      yolo: Flag = Flag(),
      @arg(doc = "disable --yolo")
      noYolo: Flag = Flag()
  ): Int =
    requireTty("create", isTty) match
      case Left(message) =>
        fail(message)
        ExitCodes.UsageError
      case Right(()) =>
        requireNonBlank("goal", goal) match
          case Left(message) =>
            fail(message)
            ExitCodes.UsageError
          case Right(()) =>
            authorParams(global, yolo, noYolo, harness) match
              case Left(message) =>
                fail(message)
                ExitCodes.UsageError
              case Right((tier, backend, yoloValue)) =>
                val workDir = os.pwd
                val globalFlows = GlobalSettings.defaultFlows
                val fileName =
                  name.getOrElse(CreateFlow.suggestedFilename(goal))
                validateFileName(fileName) match
                  case Left(message) =>
                    fail(message)
                    ExitCodes.UsageError
                  case Right(()) =>
                    safePrepareTarget(
                      tier,
                      fileName,
                      workDir,
                      globalFlows
                    ) match
                      case Left(message) =>
                        fail(message)
                        ExitCodes.ActionFailed
                      case Right(target) =>
                        ShellOutput.info(s"target flow: ${target.flowPath}")
                        val terminal = buildTerminal()
                        try
                          val ui = ShellUi.make(terminal)
                          val params = AuthorParams(
                            tier = tier,
                            target = target,
                            backend = backend,
                            yolo = yoloValue
                          )
                          exitFor(
                            AuthorAction.create(
                              goal,
                              params,
                              workDir,
                              ui,
                              terminal
                            )
                          )
                        finally terminal.close()

  @main(doc =
    "Fork an existing flow. --changes is required; yolo defaults on.\n" +
      """Example: orca fork implement.sc my-variant --changes "add a retry step""""
  )
  def fork(
      @arg(positional = true, doc = "source flow name or path")
      source: String,
      @arg(
        positional = true,
        doc = "fork's filename (default: <source>-fork.sc)"
      )
      name: Option[String] = None,
      @arg(doc = "the changes to make")
      changes: String,
      @arg(doc =
        "harness to author with: claude|codex|pi|gemini|opencode (default: configured coding agent)"
      )
      harness: Option[String] = None,
      @arg(doc =
        "save the fork under the global flows directory instead of the project's"
      )
      global: Flag = Flag(),
      @arg(doc = "let the harness run without approval prompts (default: on)")
      yolo: Flag = Flag(),
      @arg(doc = "disable --yolo")
      noYolo: Flag = Flag()
  ): Int =
    requireTty("fork", isTty) match
      case Left(message) =>
        fail(message)
        ExitCodes.UsageError
      case Right(()) =>
        requireNonBlank("changes", changes) match
          case Left(message) =>
            fail(message)
            ExitCodes.UsageError
          case Right(()) =>
            val workDir = os.pwd
            FlowResolution.resolve(source, workDir) match
              case Left(message) =>
                fail(message)
                ExitCodes.ActionFailed
              case Right(resolvedSource) =>
                authorParams(global, yolo, noYolo, harness) match
                  case Left(message) =>
                    fail(message)
                    ExitCodes.UsageError
                  case Right((tier, backend, yoloValue)) =>
                    val globalFlows = GlobalSettings.defaultFlows
                    val fileName =
                      name.getOrElse(
                        CreateFlow.forkFilenameDefault(resolvedSource.name)
                      )
                    validateFileName(fileName) match
                      case Left(message) =>
                        fail(message)
                        ExitCodes.UsageError
                      case Right(()) =>
                        safePrepareTarget(
                          tier,
                          fileName,
                          workDir,
                          globalFlows
                        ) match
                          case Left(message) =>
                            fail(message)
                            ExitCodes.ActionFailed
                          case Right(target) =>
                            ShellOutput.info(
                              s"target flow: ${target.flowPath}"
                            )
                            val terminal = buildTerminal()
                            try
                              val ui = ShellUi.make(terminal)
                              val params = AuthorParams(
                                tier = tier,
                                target = target,
                                backend = backend,
                                yolo = yoloValue
                              )
                              exitFor(
                                AuthorAction.fork(
                                  resolvedSource,
                                  changes,
                                  params,
                                  workDir,
                                  ui,
                                  terminal
                                )
                              )
                            finally terminal.close()

  /** Rejects a blank/whitespace-only `value` (`--goal`/`--changes`) with a
    * usage error — the interactive path's `promptDescription` already
    * re-prompts on blank rather than passing a degenerate description to the
    * harness, and `create`/`fork` need the same guard since they have no prompt
    * to re-ask.
    */
  private[cli] def requireNonBlank(
      argName: String,
      value: String
  ): Either[String, Unit] =
    Either.cond(!value.isBlank, (), s"--$argName can't be empty")

  /** Shared `create`/`fork` flag resolution: the tier, the harness (parsed
    * `--harness`, or the configured coding agent), and yolo (on by default,
    * `--no-yolo` to disable — mutually exclusive with an explicit `--yolo`).
    */
  private[cli] def authorParams(
      global: Flag,
      yolo: Flag,
      noYolo: Flag,
      harness: Option[String]
  ): Either[String, (CreateTier, BackendTag, Boolean)] =
    if yolo.value && noYolo.value then
      Left("--yolo and --no-yolo are mutually exclusive")
    else
      resolveHarness(harness).map: backend =>
        val tier =
          if global.value then CreateTier.Global else CreateTier.Project
        (tier, backend, !noYolo.value)

  private[cli] def resolveHarness(
      raw: Option[String]
  ): Either[String, BackendTag] =
    raw match
      case None =>
        Right(CreateFlow.configuredCodingAgent(GlobalSettings.default))
      case Some(name) =>
        AgentSpec.harnessNames
          .get(name)
          .toRight(
            s"unknown harness '$name' — valid: " +
              AgentSpec.harnessNames.keys.toList.sorted.mkString(", ")
          )

  private[cli] def exitFor(outcome: AuthorOutcome): Int = outcome match
    case AuthorOutcome.Launched(exit) => exit
    case AuthorOutcome.NotLaunched    => ExitCodes.ActionFailed

  // --- continue ---

  @main(doc =
    "Resume a recorded harness session. No selector resumes the newest one.\n" +
      "Example: orca continue --list"
  )
  def continue(
      @arg(
        positional = true,
        doc = "session index (from --list) or session name"
      )
      selector: Option[String] = None,
      @arg(doc = "print sessions instead of resuming")
      list: Flag = Flag(),
      @arg(doc = "with --list, emit JSON instead of a table")
      json: Flag = Flag()
  ): Int =
    runContinue(os.pwd, selector, list.value, json.value, isTty)

  /** `continue`'s full behavior over an explicit `workDir`/`tty` — pulled out
    * of the `@main` method so tests can point it at a temp project (seeded with
    * `.orca/cache/runs/` manifests) and simulate either a terminal or a pipe
    * without touching the real console.
    */
  private[cli] def runContinue(
      workDir: os.Path,
      selector: Option[String],
      list: Boolean,
      json: Boolean,
      tty: Boolean
  ): Int =
    val (runs, warnings) = ManifestReader.list(workDir, ManifestReader.pidAlive)
    warnings.foreach(fail)
    if list then
      printSessionListing(runs, json)
      ExitCodes.Ok
    else
      requireTty("continue", tty) match
        case Left(message) =>
          fail(message)
          ExitCodes.UsageError
        case Right(()) =>
          SessionPicker.resolveSelection(runs, selector) match
            case Left(message) =>
              fail(message)
              ExitCodes.ActionFailed
            case Right(selection) =>
              fail(resumeNotice(selection))
              val terminal = buildTerminal()
              try
                SessionAction.resume(terminal, selection) match
                  case Left(message) =>
                    fail(message)
                    ExitCodes.ActionFailed
                  // propagates the resumed harness child's raw exit code —
                  // same wraps-a-subprocess convention as run/edit.
                  case Right(exit) => exit
              finally terminal.close()

  /** The resolved session's identity, printed to stderr immediately before
    * [[SessionAction.resume]] execs its harness child (security fold-in, ADR
    * 0021 §10): mirrors what the interactive picker's row label already shows,
    * so a no-selector `orca continue` — which could otherwise resume whatever
    * session a hostile repo's `.orca/cache/runs/` manifest names, without the
    * user ever having chosen it — is visible before the exec, on this tty-gated
    * command's own terminal, giving the user a chance to Ctrl-C.
    */
  private[cli] def resumeNotice(selection: SessionSelection): String =
    SessionAction.identityNotice(
      selection,
      SessionPicker.harnessSettingsName(selection.session.harness)
    )

  private[cli] case class SessionRow(
      index: Int,
      sessionName: String,
      kind: String,
      stage: Option[String],
      harness: String,
      lastActiveAt: String,
      resumable: Boolean,
      reason: Option[String],
      crashed: Boolean
  )
  // `withTransientEmpty`/`withTransientNone` false: `--json` output is for
  // scripts, which should see an always-present `reason` key (null when
  // unset) and `shadows`/similar fields rather than a silently vanishing one.
  private given sessionRowsCodec: JsonValueCodec[List[SessionRow]] =
    ConfiguredJsonValueCodec.derived[List[SessionRow]](using
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(false)
    )

  private[cli] def sessionListingRows(runs: List[ReadRun]): List[SessionRow] =
    SessionPicker
      .resumableRows(SessionPicker.sessionRows(runs, expanded = true))
      .zipWithIndex
      .collect:
        case (
              choice @ Choice(
                SessionPicker.PickerRow.Resume(selection),
                _,
                _,
                _
              ),
              i
            ) =>
          val session = selection.session
          SessionRow(
            index = i + 1,
            sessionName = session.sessionName.getOrElse(session.agent),
            kind = session.kind,
            stage = session.stage,
            harness = SessionPicker.harnessSettingsName(session.harness),
            lastActiveAt = session.lastActiveAt,
            resumable = choice.isEnabled,
            reason = choice.disabledReason,
            crashed = selection.crashed
          )

  private def printSessionListing(runs: List[ReadRun], asJson: Boolean): Unit =
    val rows = sessionListingRows(runs)
    if asJson then println(writeToString(rows))
    else if rows.isEmpty then println("(no sessions recorded)")
    else
      val cols = rows.map: r =>
        val status =
          if r.resumable then ""
          else s"  not resumable: ${r.reason.getOrElse("")}"
        val sessionName =
          r.sessionName + (if r.crashed then " (crashed)" else "")
        (
          r.index.toString,
          sessionName,
          r.kind,
          r.stage.getOrElse(""),
          r.harness,
          r.lastActiveAt,
          status
        )
      val header =
        ("#", "session", "kind", "stage", "harness", "last active", "")
      printTable(header +: cols)

  // --- config ---

  @main(doc =
    "Show or set the global role agents (planning/coding/review).\n" +
      "No flags prints the current roles; any flag writes the given subset.\n" +
      "Example: orca config --coding codex"
  )
  def config(
      @arg(doc = "planning role, as harness[:model]")
      planning: Option[String] = None,
      @arg(doc = "coding role, as harness[:model]")
      coding: Option[String] = None,
      @arg(doc = "review role, as harness[:model]")
      review: Option[String] = None,
      @arg(doc =
        "rewrite a malformed settings file from scratch instead of refusing"
      )
      force: Flag = Flag()
  ): Int =
    runConfig(GlobalSettings.default, planning, coding, review, force.value)

  /** `config`'s full behavior over an explicit settings `path` — pulled out of
    * the `@main` method so tests can point it at a temp file instead of the
    * real user-global settings file.
    */
  private[cli] def runConfig(
      path: os.Path,
      planning: Option[String],
      coding: Option[String],
      review: Option[String],
      force: Boolean
  ): Int =
    if planning.isEmpty && coding.isEmpty && review.isEmpty then
      ConfigAction.show(path) match
        case Left(message) =>
          fail(message)
          ExitCodes.ActionFailed
        case Right(agents) =>
          println(renderAgents(agents))
          ExitCodes.Ok
    else
      parseRoleFlags(planning, coding, review) match
        case Left(message) =>
          fail(message)
          ExitCodes.UsageError
        case Right(overrides) =>
          ConfigAction.show(path) match
            case Left(parseError) if !force =>
              fail(
                s"$parseError — pass --force to rewrite it from scratch"
              )
              ExitCodes.ActionFailed
            case Left(_) =>
              ConfigAction.set(path, overrides)
              ExitCodes.Ok
            case Right(current) =>
              ConfigAction.set(path, overrides.orElse(current))
              ExitCodes.Ok

  private def parseRole(
      raw: Option[String]
  ): Either[String, Option[AgentSpec]] =
    raw match
      case None    => Right(None)
      case Some(v) => AgentSpec.parse(v).map(Some(_))

  private[cli] def parseRoleFlags(
      planning: Option[String],
      coding: Option[String],
      review: Option[String]
  ): Either[String, AgentSettings] =
    for
      p <- parseRole(planning)
      c <- parseRole(coding)
      r <- parseRole(review)
    yield AgentSettings(p, c, r)

  private[cli] def renderAgents(agents: AgentSettings): String =
    def line(role: String, spec: Option[AgentSpec]): String =
      val value = spec.fold("(not set)"): s =>
        AgentSpec.harnessNameFor(s.backend) + s.model.fold("")(":" + _)
      s"$role: $value"
    List(
      line("planning", agents.planning),
      line("coding", agents.coding),
      line("review", agents.review)
    ).mkString("\n")

  // --- rediscover-stack ---

  @main(doc =
    "Clear discovered project stack settings so the next flow run re-detects them.\n" +
      "Example: orca rediscover-stack --yes"
  )
  def rediscoverStack(
      @arg(doc = "skip the confirmation (required off a terminal)")
      yes: Flag = Flag()
  ): Int =
    runRediscoverStack(os.pwd, yes.value, isTty)

  /** `rediscover-stack`'s full behavior over an explicit `workDir` — pulled out
    * of the `@main` method so tests can point it at a temp project instead of
    * the real `os.pwd`, and inject `tty` instead of a real console.
    */
  private[cli] def runRediscoverStack(
      workDir: os.Path,
      yes: Boolean,
      tty: Boolean
  ): Int =
    StackAction.status(workDir) match
      case Left(message) =>
        fail(message)
        ExitCodes.ActionFailed
      case Right(StackStatus.NoSettings | StackStatus.NoStackLines) =>
        ShellOutput.info(StackAction.noSettingsMessage)
        ExitCodes.Ok
      case Right(StackStatus.Present(stack, content)) =>
        if yes then
          StackAction.clearIfConfirmed(workDir, stack, content, () => true)
          ExitCodes.Ok
        else if tty then confirmAndClear(workDir, stack, content)
        else
          StackAction.clearIfConfirmed(workDir, stack, content, () => false)
          fail(
            "pass --yes to confirm clearing stack settings (non-interactive)"
          )
          ExitCodes.UsageError

  private def confirmAndClear(
      workDir: os.Path,
      stack: orca.StackSettings,
      content: String
  ): Int =
    val terminal = buildTerminal()
    try
      val ui = ShellUi.make(terminal)
      StackAction.clearIfConfirmed(
        workDir,
        stack,
        content,
        () =>
          ui.confirm(StackAction.clearConfirmPrompt, default = false) match
            case UiOutcome.Selected(true) => true
            case _                        => false
      )
      ExitCodes.Ok
    finally terminal.close()

  // --- list ---

  @main(doc =
    "List discovered flows across the project/global/built-in tiers.\n" +
      "Example: orca list --json"
  )
  def list(
      @arg(doc = "emit JSON instead of a table")
      json: Flag = Flag()
  ): Int =
    runList(os.pwd, json.value)

  /** `list`'s full behavior over an explicit `workDir` — pulled out of the
    * `@main` method so tests can point it at a temp project instead of the real
    * `os.pwd`.
    */
  private[cli] def runList(workDir: os.Path, json: Boolean): Int =
    FlowResolution.list(workDir) match
      case Left(message) =>
        fail(message)
        ExitCodes.ActionFailed
      case Right(flows) =>
        if json then println(writeToString(flows.map(toFlowRow)))
        else printFlowTable(flows)
        ExitCodes.Ok

  private[cli] case class FlowRow(
      name: String,
      description: Option[String],
      origin: String,
      path: String,
      shadows: List[String]
  )
  private given flowRowsCodec: JsonValueCodec[List[FlowRow]] =
    ConfiguredJsonValueCodec.derived[List[FlowRow]](using
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(false)
    )

  private[cli] def toFlowRow(flow: DiscoveredFlow): FlowRow =
    FlowRow(
      flow.name,
      flow.description,
      flow.origin.originLabel,
      flow.path.toString,
      flow.shadows.map(_.originLabel)
    )

  private def printFlowTable(flows: List[DiscoveredFlow]): Unit =
    if flows.isEmpty then println("(no flows found)")
    else
      val cols = flows.map: f =>
        val shadows =
          if f.shadows.isEmpty then ""
          else s"shadows ${f.shadows.map(_.originLabel).mkString(", ")}"
        (
          f.name,
          f.description.getOrElse("(no description)"),
          f.origin.originLabel,
          shadows
        )
      val header = ("name", "description", "origin", "")
      printTable(header +: cols)

  /** Space-padded columns, header row included — the shared rendering
    * [[printFlowTable]] and [[printSessionListing]] both use. Trailing empty
    * cells in a row (an unshadowed flow, a resumable session) print no
    * padding-driven trailing whitespace.
    */
  private def printTable(rows: Seq[Product]): Unit =
    val asRows = rows.map(_.productIterator.map(_.toString).toIndexedSeq)
    val widths = asRows.transpose.map(_.map(_.length).max)
    asRows.foreach: cells =>
      val line = cells
        .zip(widths)
        .map((cell, width) => cell.padTo(width, ' '))
        .mkString("  ")
        .stripTrailing()
      println(line)

  // --- shared: tty gate + terminal ---

  private def isTty: Boolean = System.console() != null

  /** The mandatory gate before touching any interactive UI or child-exec
    * command (ADR 0021 §4/§10): `create`/`fork`/`edit`/`continue`'s resume all
    * exec an interactive child or (opencode) confirm via [[ShellUi]], which
    * NPEs off a real tty — so this runs BEFORE any [[Terminal]] or [[ShellUi]]
    * is built. `tty` is injected (production: [[isTty]]) so the decision is
    * unit-testable without a real console.
    */
  private[cli] def requireTty(
      command: String,
      tty: Boolean
  ): Either[String, Unit] =
    Either.cond(
      tty,
      (),
      s"`orca $command` needs a terminal; run it interactively"
    )

  private def buildTerminal(): Terminal =
    TerminalBuilder.builder().system(true).dumb(true).build()
