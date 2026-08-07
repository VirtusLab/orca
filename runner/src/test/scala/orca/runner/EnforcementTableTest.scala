package orca.runner

import orca.agents.{AutoApprove, BackendTag, Enforcement, ToolSet, TurnDispatch}
import orca.backend.AgentBackend
import orca.subprocess.StubCliRunner
import orca.tools.claude.ClaudeBackend
import orca.tools.codex.CodexBackend
import orca.tools.gemini.GeminiBackend
import orca.tools.opencode.OpencodeBackend
import orca.tools.pi.PiBackend

import ox.supervised
import orca.testkit.TempDirs

/** The [[AutoApprove]] values the matrix distinguishes. `Only` is split by
  * emptiness because "pre-approve nothing" is a shape some backends encode as
  * no flag at all, and it would otherwise go untested.
  */
private enum ApproveShape(val label: String, val sample: AutoApprove):
  case All extends ApproveShape("All", AutoApprove.All)
  case OnlySome extends ApproveShape("Only(_)", AutoApprove.Only(Set("Read")))
  case OnlyEmpty extends ApproveShape("Only()", AutoApprove.Only(Set.empty))

/** Machine-checked source of truth for the per-backend enforcement matrix (the
  * `Enforcement` a `(ToolSet, AutoApprove, TurnDispatch)` combination gets on
  * each backend), and the renderer of the block AGENTS.md carries.
  *
  * The expectations below are deliberately a second, hand-written copy of what
  * the backends declare: editing a backend's cell without editing its row here
  * fails. The product is walked in full, so a new `BackendTag`, `ToolSet`,
  * approve shape, or dispatch fails the moment its enum grows rather than going
  * silently unchecked.
  *
  * Every backend's `enforcementCell` is a pure function delegating to its
  * `*Args`, so construction is cheap: a [[StubCliRunner]] the method never
  * touches. opencode builds its server object eagerly but the process spawn
  * stays lazy, so the [[StubCliRunner]] is never spawned.
  */
class EnforcementTableTest extends munit.FunSuite:

  import Enforcement.*

  /** One expected row, by backend. Named arguments at the call sites, and a
    * signature a sixth backend has to widen, keep the columns from drifting.
    */
  private def row(
      claude: Enforcement,
      codex: Enforcement,
      gemini: Enforcement,
      opencode: Enforcement,
      pi: Enforcement
  ): Map[BackendTag, Enforcement] =
    Map(
      BackendTag.ClaudeCode -> claude,
      BackendTag.Codex -> codex,
      BackendTag.Gemini -> gemini,
      BackendTag.Opencode -> opencode,
      BackendTag.Pi -> pi
    )

  private val readOnlyFresh: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = Hard,
    gemini = PromptOnly,
    opencode = Hard,
    pi = Hard
  )

  /** codex `exec resume` takes no sandbox flag, so its read-only tiers fall
    * back to the prompt on a resumed turn.
    */
  private val readOnlyResumed: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = PromptOnly,
    gemini = PromptOnly,
    opencode = Hard,
    pi = Hard
  )

  private val networkOnly: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = PromptOnly,
    gemini = PromptOnly,
    opencode = Hard,
    pi = PromptOnly
  )

  private val fullAll: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = Hard,
    gemini = Hard,
    opencode = Ignored,
    pi = Ignored
  )

  private val fullOnlyFresh: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = SandboxApprox,
    gemini = Ignored,
    opencode = Ignored,
    pi = Ignored
  )

  private val fullOnlyResumed: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = Ignored,
    gemini = Ignored,
    opencode = Ignored,
    pi = Ignored
  )

  private val expected: Map[
    (ToolSet, ApproveShape, TurnDispatch),
    Map[BackendTag, Enforcement]
  ] =
    (for
      shape <- ApproveShape.values.toList
      // The read-only tiers ignore autoApprove on every backend, so one row
      // stands for all three shapes — pinned by the product walk, not assumed.
      entry <- List(
        (ToolSet.ReadOnly, shape, TurnDispatch.Fresh) -> readOnlyFresh,
        (ToolSet.ReadOnly, shape, TurnDispatch.Resumed) -> readOnlyResumed,
        (ToolSet.NetworkOnly, shape, TurnDispatch.Fresh) -> networkOnly,
        (ToolSet.NetworkOnly, shape, TurnDispatch.Resumed) -> networkOnly
      )
    yield entry).toMap ++ Map(
      (ToolSet.Full, ApproveShape.All, TurnDispatch.Fresh) -> fullAll,
      (ToolSet.Full, ApproveShape.All, TurnDispatch.Resumed) -> fullAll,
      (
        ToolSet.Full,
        ApproveShape.OnlySome,
        TurnDispatch.Fresh
      ) -> fullOnlyFresh,
      (ToolSet.Full, ApproveShape.OnlySome, TurnDispatch.Resumed) ->
        fullOnlyResumed,
      (ToolSet.Full, ApproveShape.OnlyEmpty, TurnDispatch.Fresh) ->
        fullOnlyFresh,
      (ToolSet.Full, ApproveShape.OnlyEmpty, TurnDispatch.Resumed) ->
        fullOnlyResumed
    )

  test("every backend tag has a backend wired into the matrix"):
    withDeclared: declared =>
      assertEquals(declared.keySet.map(_._1), BackendTag.values.toSet)

  test("every cell of the product matches what its backend declares"):
    withDeclared: declared =>
      // Collect every divergence and fail once with the full list, so one run
      // surfaces all of them rather than stopping at the first.
      val problems = for
        tag <- BackendTag.values.toList
        tools <- ToolSet.values.toList
        shape <- ApproveShape.values.toList
        dispatch <- TurnDispatch.values.toList
        where = s"$tag / $tools / ${shape.label} / $dispatch"
        problem <- expected
          .get((tools, shape, dispatch))
          .flatMap(_.get(tag)) match
          case None => Some(s"$where: no expectation")
          case Some(want) =>
            declared
              .get((tag, tools, shape, dispatch))
              .filter(_ != want)
              .map(got => s"$where: want $want, got $got")
      yield problem
      assert(problems.isEmpty, problems.mkString("\n"))

  test("AGENTS.md carries the block rendered from the declared cells"):
    withDeclared: declared =>
      val block = renderBlock(declared)
      assert(
        agentsMd.contains(block),
        s"AGENTS.md's enforcement block is stale — replace it with:\n\n$block"
      )

  /** The declared level for every (backend, tools, approve shape, dispatch). */
  private def withDeclared[A](
      use: Map[
        (BackendTag, ToolSet, ApproveShape, TurnDispatch),
        Enforcement
      ] => A
  ): A =
    supervised:
      val cli = new StubCliRunner()
      val backends = List[AgentBackend[?]](
        new ClaudeBackend(cli),
        new CodexBackend(cli),
        new GeminiBackend(cli),
        OpencodeBackend(cli, TempDirs.dir()),
        PiBackend.forInspection(cli)
      )
      use(
        (for
          backend <- backends
          tools <- ToolSet.values.toList
          shape <- ApproveShape.values.toList
          dispatch <- TurnDispatch.values.toList
        yield (backend.tag, tools, shape, dispatch) ->
          backend.enforcementCell(tools, shape.sample, dispatch).level).toMap
      )

  /** Renders the markdown block AGENTS.md carries: the fresh-turn table, then
    * the resumed turns that classify differently. Indented two spaces, which is
    * where it sits inside AGENTS.md's bullet.
    *
    * Table rows collapse approve shapes that classify identically on every
    * backend AND both dispatches (`*` when all of them do), so a shape that
    * only differs on resume can't hide inside a merged row.
    */
  private def renderBlock(
      declared: Map[
        (BackendTag, ToolSet, ApproveShape, TurnDispatch),
        Enforcement
      ]
  ): String =
    def levels(
        tools: ToolSet,
        shape: ApproveShape,
        dispatch: TurnDispatch
    ): List[String] =
      BackendTag.values.toList.map(declared(_, tools, shape, dispatch).toString)
    def bothDispatches(tools: ToolSet, shape: ApproveShape): List[String] =
      TurnDispatch.values.toList.flatMap(levels(tools, shape, _))

    val runs = for
      tools <- ToolSet.values.toList
      run <- shapeRuns(tools, bothDispatches)
    yield (tools, run)

    val header = "tools, approve" :: BackendTag.values.toList.map(_.wireName)
    val body = runs.map: (tools, run) =>
      s"$tools, ${runLabel(run)}" ::
        levels(tools, run.head, TurnDispatch.Fresh)
    val widths = (header :: body).transpose.map(_.map(_.length).max)
    def line(cells: List[String]) =
      cells
        .zip(widths)
        .map((cell, width) => cell.padTo(width, ' '))
        .mkString("  | ", " | ", " |")
    val separator = widths.map(w => "-" * (w + 2)).mkString("  |", "|", "|")
    val table = (line(header) :: separator :: body.map(line)).mkString("\n")

    val deltas = for
      (tools, run) <- runs
      tag <- BackendTag.values.toList
      fresh = declared((tag, tools, run.head, TurnDispatch.Fresh))
      resumed = declared((tag, tools, run.head, TurnDispatch.Resumed))
      if fresh != resumed
    yield s"  - ${tag.wireName}, $tools, ${runLabel(run)}: $resumed, not $fresh"
    val note =
      if deltas.isEmpty then "  A resumed turn is classified the same."
      else
        ("  A resumed turn is classified the same, except:" :: deltas)
          .mkString("\n")

    s"$table\n\n$note"

  /** Consecutive approve shapes whose whole row of levels is identical, so the
    * rendered table collapses them into one row.
    */
  private def shapeRuns(
      tools: ToolSet,
      levels: (ToolSet, ApproveShape) => List[String]
  ): List[List[ApproveShape]] =
    ApproveShape.values.toList.foldLeft(List.empty[List[ApproveShape]]):
      (runs, shape) =>
        runs.lastOption match
          case Some(run) if levels(tools, run.head) == levels(tools, shape) =>
            runs.init :+ (run :+ shape)
          case _ => runs :+ List(shape)

  private def runLabel(run: List[ApproveShape]): String =
    if run.sizeIs == ApproveShape.values.length then "*"
    else run.map(_.label).mkString(" / ")

  /** AGENTS.md's text. sbt forks tests with the module directory as the working
    * directory, so the repository root is found by walking up.
    */
  private def agentsMd: String =
    val root = Iterator
      .iterate(os.pwd)(_ / os.up)
      .takeWhile(_ != os.root)
      .find(dir => os.exists(dir / "AGENTS.md"))
      .getOrElse(fail(s"no AGENTS.md above ${os.pwd}"))
    os.read(root / "AGENTS.md")
