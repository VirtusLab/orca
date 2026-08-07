package orca.runner

import orca.agents.{AutoApprove, BackendTag, Enforcement, ToolSet}
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
  * `Enforcement` a `(ToolSet, AutoApprove)` combination gets on each backend),
  * and the renderer of the table AGENTS.md carries.
  *
  * The expectations below are deliberately a second, hand-written copy of what
  * the backends declare: editing a backend's cell without editing its row here
  * fails. The product is walked in full, so a new `BackendTag`, `ToolSet`, or
  * approve shape fails the moment its enum grows rather than going silently
  * unchecked.
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

  private val readOnlyRow: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = Hard,
    gemini = PromptOnly,
    opencode = Hard,
    pi = Hard
  )

  private val networkOnlyRow: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = PromptOnly,
    gemini = PromptOnly,
    opencode = Hard,
    pi = PromptOnly
  )

  private val fullOnlyRow: Map[BackendTag, Enforcement] = row(
    claude = Hard,
    codex = SandboxApprox,
    gemini = Ignored,
    opencode = Ignored,
    pi = Ignored
  )

  private val expected
      : Map[(ToolSet, ApproveShape), Map[BackendTag, Enforcement]] =
    Map(
      // ReadOnly / NetworkOnly ignore autoApprove on every backend, so their
      // three rows agree by construction — pinned, not assumed.
      (ToolSet.ReadOnly, ApproveShape.All) ->
        readOnlyRow,
      (ToolSet.ReadOnly, ApproveShape.OnlySome) ->
        readOnlyRow,
      (ToolSet.ReadOnly, ApproveShape.OnlyEmpty) ->
        readOnlyRow,
      (ToolSet.NetworkOnly, ApproveShape.All) ->
        networkOnlyRow,
      (ToolSet.NetworkOnly, ApproveShape.OnlySome) ->
        networkOnlyRow,
      (ToolSet.NetworkOnly, ApproveShape.OnlyEmpty) ->
        networkOnlyRow,
      (ToolSet.Full, ApproveShape.All) -> row(
        claude = Hard,
        codex = Hard,
        gemini = Hard,
        opencode = Ignored,
        pi = Ignored
      ),
      (ToolSet.Full, ApproveShape.OnlySome) ->
        fullOnlyRow,
      (ToolSet.Full, ApproveShape.OnlyEmpty) ->
        fullOnlyRow
    )

  test("every backend tag has a backend wired into the matrix"):
    withDeclared: declared =>
      val wired = declared.keySet.map(_._1)
      assertEquals(wired, BackendTag.values.toSet)

  test("every cell of the product matches what its backend declares"):
    withDeclared: declared =>
      // Collect every divergence and fail once with the full list, so one run
      // surfaces all of them rather than stopping at the first.
      val problems = for
        tag <- BackendTag.values.toList
        tools <- ToolSet.values.toList
        shape <- ApproveShape.values.toList
        problem <- expected.get((tools, shape)).flatMap(_.get(tag)) match
          case None => Some(s"$tag / $tools / ${shape.label}: no expectation")
          case Some(want) =>
            declared
              .get((tag, tools, shape))
              .filter(_ != want)
              .map(got =>
                s"$tag / $tools / ${shape.label}: want $want, got $got"
              )
      yield problem
      assert(problems.isEmpty, problems.mkString("\n"))

  test("AGENTS.md carries the table rendered from the declared cells"):
    withDeclared: declared =>
      val table = renderTable(declared)
      assert(
        agentsMd.contains(table),
        s"AGENTS.md's enforcement table is stale — replace it with:\n\n$table"
      )

  /** The declared level for every (backend, tools, approve shape). */
  private def withDeclared[A](
      use: Map[(BackendTag, ToolSet, ApproveShape), Enforcement] => A
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
        yield (backend.tag, tools, shape) ->
          backend.enforcementCell(tools, shape.sample).level).toMap
      )

  /** Renders the matrix as the markdown block AGENTS.md carries: one column per
    * backend, one row per run of approve shapes that classify identically on
    * every backend (`*` when all of them do, which is why the read-only tiers
    * are one row each). Indented two spaces to sit inside AGENTS.md's bullet.
    */
  private def renderTable(
      declared: Map[(BackendTag, ToolSet, ApproveShape), Enforcement]
  ): String =
    def levels(tools: ToolSet, shape: ApproveShape): List[String] =
      BackendTag.values.toList.map(tag =>
        declared((tag, tools, shape)).toString
      )

    val header = "tools, approve" :: BackendTag.values.toList.map(_.wireName)
    val body = for
      tools <- ToolSet.values.toList
      group <- shapeRuns(tools, levels)
    yield s"$tools, ${runLabel(group)}" :: levels(tools, group.head)

    val widths = (header :: body).transpose.map(_.map(_.length).max)
    def line(cells: List[String]) =
      cells
        .zip(widths)
        .map((c, w) => c.padTo(w, ' '))
        .mkString("  | ", " | ", " |")
    val separator = widths.map(w => "-" * (w + 2)).mkString("  |", "|", "|")
    (line(header) :: separator :: body.map(line)).mkString("\n")

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
