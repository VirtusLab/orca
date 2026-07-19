package orca.shell.cli

import mainargs.{Flag, ParserForMethods, Renderer, Util, arg, main}
import org.jline.terminal.Terminal
import orca.settings.GlobalSettings
import orca.shell.ui.ShellUi

/** Exit codes shared by every subcommand (ADR 0021 §10/§4). */
private[shell] object ExitCodes:
  val Ok = 0
  val ActionFailed = 1
  val UsageError = 2
  val SignalKilled = 130

/** A subcommand failure carrying the exact process exit code it maps to — the
  * `Left` half of each per-command handler's for-comprehension, so the message
  * and its code travel together to [[Cli.complete]] instead of every error
  * branch re-pairing a `diagnostic(...)` with a literal `ExitCodes` value.
  */
private[cli] case class CliFailure(message: String, exitCode: Int)

/** The non-interactive CLI's mainargs subcommand surface (ADR 0021 §10): one
  * `@main` method per verb, each a thin parse-and-delegate to the matching
  * per-command handler in `cli/` and returning the process exit code rather
  * than calling `sys.exit` itself — [[orca.shell.Main.main]] is the only place
  * that exits, once [[dispatch]] has returned. The handlers hand off to the
  * `actions/` layer the interactive menu also calls, so behavior matches
  * `Main`'s prompting flows exactly, minus the prompts.
  */
private[shell] object Cli:

  private val nameMapper = Util.kebabCaseNameMapper

  /** Every CLI-path diagnostic (errors, `ManifestReader` warnings) goes here,
    * plain text with no ANSI/fansi coloring — unlike
    * [[orca.shell.ui.ShellOutput.error]], which is stdout-and-colored for the
    * interactive shell. `--json`/table data is exclusively `println` (stdout);
    * this is the only other output a CLI subcommand ever produces, so routing
    * it to stderr is what keeps e.g. `orca continue --list --json | jq`
    * parseable.
    */
  private[cli] def diagnostic(msg: String): Unit = Console.err.println(msg)

  private[cli] def usageFailure(message: String): CliFailure =
    CliFailure(message, ExitCodes.UsageError)

  private[cli] def actionFailure(message: String): CliFailure =
    CliFailure(message, ExitCodes.ActionFailed)

  /** Runs a handler's for-comprehension to a process exit code: a `Left` is
    * [[diagnostic]] emitted to stderr and yields its carried exit code, a
    * `Right` is the subcommand's own returned code.
    */
  private[cli] def complete(program: Either[CliFailure, Int]): Int =
    program match
      case Left(failure) =>
        diagnostic(failure.message)
        failure.exitCode
      case Right(exit) => exit

  /** Builds the shell's terminal, runs `body` over it, and always closes it —
    * the bracket every child-execing subcommand (run/edit/create/fork/continue/
    * rediscover-stack) shares.
    */
  private[cli] def withTerminal[A](body: Terminal => A): A =
    val terminal = ShellUi.buildTerminal()
    try body(terminal)
    finally terminal.close()

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
    RunCli.run(flow, task, verbose.value, honorPin.value, os.pwd, isTty)

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
    ViewCli.run(flow, plain.value, color.value, isTty, os.pwd)

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
    EditCli.run(flow, to, isTty, os.pwd)

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
    AuthorCli.create(name, goal, harness, global, yolo, noYolo, isTty, os.pwd)

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
    AuthorCli.fork(
      source,
      name,
      changes,
      harness,
      global,
      yolo,
      noYolo,
      isTty,
      os.pwd
    )

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
    ContinueCli.runContinue(os.pwd, selector, list.value, json.value, isTty)

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
    ConfigCli.runConfig(
      GlobalSettings.default,
      planning,
      coding,
      review,
      force.value
    )

  @main(doc =
    "Clear discovered project stack settings so the next flow run re-detects them.\n" +
      "Example: orca rediscover-stack --yes"
  )
  def rediscoverStack(
      @arg(doc = "skip the confirmation (required off a terminal)")
      yes: Flag = Flag()
  ): Int =
    StackCli.runRediscoverStack(os.pwd, yes.value, isTty)

  @main(doc =
    "List discovered flows across the project/global/built-in tiers.\n" +
      "Example: orca list --json"
  )
  def list(
      @arg(doc = "emit JSON instead of a table")
      json: Flag = Flag()
  ): Int =
    ListCli.runList(os.pwd, json.value)

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
