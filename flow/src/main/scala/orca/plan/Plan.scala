package orca.plan

import orca.{FlowContext, OrcaFlowException}
import orca.llm.{Announce, BackendTag, CanAskUser, JsonData, LlmTool, given}

/** A development plan: an ordered list of [[Task]]s the agent will work
  * through, all on a single branch named by `epicId` (kebab-case, used directly
  * as the git branch name), plus a `brief` — a concise codebase briefing the
  * implementing agents rely on so they don't have to rediscover the layout.
  *
  * The brief is always present: it is produced as part of the planner's
  * structured output, not a separate turn, and feeds the implementer session
  * seed (ADR 0018 §2.6).
  *
  * ==Planning grid==
  *
  * The planning entry points form a `mode × operation` grid. Mode and operation
  * are orthogonal — every combination is valid:
  *
  *   - **mode** — [[autonomous]] (single agentic turn, read-only, no human) or
  *     [[interactive]] (a conversation the agent can drive via `ask_user`).
  *     Chosen by the nested object so it's visible at the call site, mirroring
  *     `claude.autonomous.run` / `claude.resultAs[O].interactive.run`.
  *   - **operation** — `from` (produce a [[Plan]] directly), `assessThenPlan`
  *     (skeptically assess first, returning a [[Verdict]] that either proceeds
  *     with a plan or rejects), or `triage` (classify a bug report into a
  *     [[Triage]] verdict).
  *
  * Every cell returns a [[Sessioned]] — the result plus the agent session that
  * produced it. From a `Sessioned[B, Plan]` the same session can be continued
  * read-only into [[Sessioned.reviewed]] (self-critique), or discarded for a
  * fresh implementer session.
  *
  * `derives JsonData` so the structured-output path works directly: the helper
  * methods consume Orca's auto-generated JSON schema; no caller-side
  * serialization is needed. As a single case class it is also a valid stage
  * result (ADR 0018 §2.3) — the stage log, not a plan file, is what resume
  * reads.
  */
case class Plan(
    epicId: String,
    description: String,
    tasks: List[Task],
    brief: String
) derives JsonData:

  /** First task whose `completed` flag is false, in declaration order. `None`
    * means the plan is fully done.
    */
  def firstIncomplete: Option[Task] = tasks.find(!_.completed)

  /** Mark the task with the given `title` complete, leaving the others
    * untouched. Returns the same plan if no task matches.
    */
  def markComplete(title: Title): Plan =
    copy(tasks = tasks.map(t => if t.title == title then t.markComplete else t))

  /** Prompt for `task`: its description, with the shared brief prepended. */
  def taskPrompt(task: Task): String = s"$brief\n\n---\n\n${task.description}"

object Plan:

  /** Autonomous planning — a single agentic turn, no human in the loop. The
    * agent runs `NetworkOnly` (`.withNetworkOnly`): it can verify claims via
    * Read/Grep and read-only network (issues/PRs/web) but can't edit during the
    * planning turn (see [[autonomousResult]] for the per-backend guarantee).
    * Sibling of [[interactive]]; the choice between the two is visible at the
    * call site (`Plan.autonomous.from(...)` vs `Plan.interactive.from(...)`),
    * mirroring `LlmTool`'s own `autonomous` / `interactive` split.
    *
    * Each operation returns a [[Sessioned]]: the read-only planning turn's
    * session is still resumable by a later writable call, so the caller can
    * continue it into implementation or discard it for a fresh session.
    *
    * No `CanAskUser` bound (unlike [[interactive]]): an autonomous turn never
    * calls `ask_user`, so it works with any backend.
    */
  object autonomous:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Sessioned[B, Plan] =
      autonomousResult[B, Plan, Plan](llm, userPrompt, instructions)(identity)

    /** Skeptically assess `userPrompt` (typically a bug/feature report) and
      * either proceed with a plan or reject with a [[Verdict.Rejection]] the
      * caller surfaces to whoever filed it.
      */
    def assessThenPlan[B <: BackendTag](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext): Sessioned[B, Verdict[Plan]] =
      autonomousResult[B, AssessedPlan, Verdict[Plan]](
        llm,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict (not-a-bug / untestable
      * / testable).
      */
    def triage[B <: BackendTag](
        report: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext): Sessioned[B, Triage] =
      autonomousResult[B, BugTriage, Triage](llm, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

  /** Interactive planning — the LLM call opens a conversation the user can
    * drive (clarifying questions, refinements) before the agent produces the
    * result. Not read-only: claude's plan mode would disable the `ask_user` MCP
    * tool, so the agent runs with normal permissions; the prompt asks it not to
    * edit, and the user sees any violation.
    *
    * The `B: CanAskUser` constraint means these compile only with backends that
    * can host an `ask_user` tool — claude and codex (both via the shared
    * `AskUserMcpServer`) and pi (via Orca's temporary ask-user extension). A
    * future stdin-only backend would fail this at compile time. Use
    * [[autonomous]] when no mid-session questions are needed.
    *
    * Each operation returns a [[Sessioned]] so the conversation can carry into
    * implementation (e.g. a triage agent's exploration informs the fix).
    */
  object interactive:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag: CanAskUser](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Sessioned[B, Plan] =
      interactiveResult[B, Plan, Plan](llm, userPrompt, instructions)(identity)

    /** Skeptically assess `userPrompt`, but able to ask the reporter clarifying
      * questions mid-turn rather than only rejecting with a
      * [[Verdict.RejectionKind.Question]].
      */
    def assessThenPlan[B <: BackendTag: CanAskUser](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext): Sessioned[B, Verdict[Plan]] =
      interactiveResult[B, AssessedPlan, Verdict[Plan]](
        llm,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict, able to ask the
      * reporter clarifying questions before deciding.
      */
    def triage[B <: BackendTag: CanAskUser](
        report: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext): Sessioned[B, Triage] =
      interactiveResult[B, BugTriage, Triage](llm, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

  /** Append the operation's instruction block to the caller's input. */
  private def withInstructions(input: String, instructions: String): String =
    s"$input\n\n$instructions"

  /** Surface a structured-contract violation (a `toVerdict` / `toTriage`
    * `Left`) as a flow failure. The decode succeeded but the field combination
    * was incoherent past the retry loop, so it's a system-level failure.
    */
  private def getOrFail[A](result: Either[String, A]): A =
    result.fold(msg => throw OrcaFlowException(msg), identity)

  /** Run one autonomous turn producing wire type `O`, convert it to the public
    * result `A`, and pair it with the session. Shared by every `autonomous.*`
    * operation (`from`, `assessThenPlan`, `triage`).
    *
    * Runs `NetworkOnly`: reads plus read-only network, so the planner can fetch
    * an issue/PR it was pointed at and verify external claims. Edits stay
    * blocked (hard on claude/gemini/opencode; prompt-only on pi/codex — the
    * planning prompts forbid edits). Reviewers and the post-planning `reviewed`
    * turn use plain `withReadOnly` instead — no network, hard no-edit
    * everywhere.
    */
  private def autonomousResult[B <: BackendTag, O: JsonData: Announce, A](
      llm: LlmTool[B],
      input: String,
      instructions: String
  )(convert: O => A)(using FlowContext): Sessioned[B, A] =
    val (sessionId, raw) = llm.withNetworkOnly
      .resultAs[O]
      .autonomous
      .run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  /** Interactive counterpart to [[autonomousResult]]. */
  private def interactiveResult[
      B <: BackendTag: CanAskUser,
      O: JsonData: Announce,
      A
  ](
      llm: LlmTool[B],
      input: String,
      instructions: String
  )(convert: O => A)(using FlowContext): Sessioned[B, A] =
    val (sessionId, raw) =
      llm.resultAs[O].interactive.run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  // == Post-planning step on a produced plan ==
  //
  // `reviewed` resumes the planning session read-only, reusing the planner's
  // exploration. Defined here so it's in the implicit scope of
  // `Sessioned[B, Plan]` — no extra import needed.

  extension [B <: BackendTag](sp: Sessioned[B, Plan])
    /** Resume the planning session for a critical self-review, returning the
      * improved plan (brief included) paired with the (same) session.
      */
    def reviewed(
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Review
    )(using FlowContext): Sessioned[B, Plan] =
      val (sessionId, improved) = llm.withReadOnly
        .resultAs[Plan]
        .autonomous
        .run(s"$instructions\n\n${render(sp.value)}", session = sp.sessionId)
      Sessioned(sessionId, improved)

  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies the
    * picture; a planning failure is more useful as an explicit `fail(...)` from
    * the script.
    */
  given Announce[Plan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      val header =
        s"Planned ${plan.tasks.size} task$plural on branch '${plan.epicId}':"
      val body = plan.tasks.map(t => s"  - ${t.title}").mkString("\n")
      s"$header\n$body"

  /** Parse a plan from its markdown representation, including its trailing `##
    * Brief` section. Strict — throws [[PlanParseException]] on any deviation
    * from the `# Plan:` / `## Task:` / `## Brief` schema. CRLF line endings and
    * a leading BOM are normalised first.
    *
    * This is the inverse of [[render]]; it exists only for that round-trip.
    * Resume never reads a rendered plan back — the stage log is the sole resume
    * mechanism (ADR 0018 §2.8); [[render]] is cosmetic, a human checklist.
    */
  def parse(markdown: String): Plan =
    val normalised = markdown.stripPrefix("﻿").replace("\r\n", "\n")
    val (planPart, brief) = splitBrief(normalised)
    parsePlan(planPart, brief.getOrElse(""))

  /** Render a plan into a human-readable markdown checklist, with the brief as
    * a trailing `## Brief` section. Round-trips through [[parse]] without
    * information loss. Cosmetic only (a checklist for users / `reviewed`'s
    * input) — never read back for resume.
    */
  def render(plan: Plan): String =
    val base = renderPlan(plan)
    if plan.brief.trim.isEmpty then base
    else s"$base\n## Brief\n\n${plan.brief.stripLineEnd}\n"

  // --- Brief section (rendered last, parsed first) ---

  private val BriefHeaderPattern = "^##\\s+Brief\\s*$".r

  /** Split a normalised document into its plan part and optional brief. The
    * brief is the text after the first `## Brief` heading that follows the
    * tasks — searching only past the first `## Task:` stops a stray `## Brief`
    * in the description from swallowing them, while still letting the brief
    * body carry its own `##` headings.
    */
  private def splitBrief(normalised: String): (String, Option[String]) =
    val lines = normalised.linesIterator.toList
    val afterFirstTask = lines.indexWhere(TaskHeaderPattern.matches) + 1
    lines.indexWhere(BriefHeaderPattern.matches, afterFirstTask) match
      case -1 => (normalised, None)
      case i  =>
        // Drop the blank line `render` writes after the heading; keep the rest
        // verbatim (a brief may be indented).
        val brief =
          lines.drop(i + 1).dropWhile(_.isEmpty).mkString("\n").stripLineEnd
        (
          lines.take(i).mkString("\n"),
          if brief.isEmpty then None else Some(brief)
        )

  private def renderPlan(plan: Plan): String =
    val header = s"# Plan: ${plan.epicId}\n"
    val descriptionBlock =
      if plan.description.trim.isEmpty then ""
      else s"\n${plan.description.stripLineEnd}\n"
    val body = plan.tasks
      .map: t =>
        val checkbox = if t.completed then "[x]" else "[ ]"
        s"\n## Task: ${t.title}\nStatus: $checkbox\n\n${t.description.stripLineEnd}\n"
      .mkString
    header + descriptionBlock + body

  // --- Parser internals ---

  private val HeaderPattern = "^# Plan:\\s*(\\S.*)$".r
  private val TaskHeaderPattern = "^## Task:\\s*(\\S.*)$".r
  private val StatusPattern = "^Status:\\s*\\[(.)\\]\\s*$".r

  private def parsePlan(planMarkdown: String, brief: String): Plan =
    val lines = planMarkdown.linesIterator.toList
    val epicId = parseHeader(lines)
    val description = parseDescription(lines)
    val taskBlocks = splitTaskBlocks(lines)
    if taskBlocks.isEmpty then throw PlanParseException("Plan has no tasks")
    Plan(epicId, description, taskBlocks.map(parseTask), brief)

  private def parseHeader(lines: List[String]): String =
    lines.find(_.trim.nonEmpty) match
      case Some(HeaderPattern(id)) => id.trim
      case other =>
        throw PlanParseException(
          s"Expected first non-blank line to match `# Plan: <epicId>`; got: ${other.getOrElse("(empty file)")}"
        )

  /** Description sits between the `# Plan:` header and the first `## Task:`
    * heading. Empty when the file goes straight from the header into tasks.
    */
  private def parseDescription(lines: List[String]): String =
    val afterHeader = lines.dropWhile(l => !HeaderPattern.matches(l)).drop(1)
    afterHeader
      .takeWhile(l => !TaskHeaderPattern.matches(l))
      .mkString("\n")
      .trim

  private def splitTaskBlocks(lines: List[String]): List[List[String]] =
    val blocks = collection.mutable.ListBuffer[List[String]]()
    var current = collection.mutable.ListBuffer[String]()
    var inTask = false
    for line <- lines do
      if TaskHeaderPattern.matches(line) then
        if inTask then blocks += current.toList
        current = collection.mutable.ListBuffer(line)
        inTask = true
      else if inTask then current += line
    if inTask then blocks += current.toList
    blocks.toList

  private def parseTask(block: List[String]): Task =
    val title = block.headOption match
      case Some(TaskHeaderPattern(t)) => t.trim
      case _ =>
        throw PlanParseException(
          s"Task block doesn't start with `## Task: <title>`: ${block.headOption.getOrElse("")}"
        )
    val rest = block.tail.dropWhile(_.trim.isEmpty)
    val (statusLine, afterStatus) = rest.headOption match
      case Some(line @ StatusPattern(_)) => (line, rest.tail)
      case _ =>
        throw PlanParseException(
          s"Task '$title' is missing a `Status: [ ]` / `Status: [x]` line"
        )
    val completed = statusLine match
      case StatusPattern(" ") => false
      case StatusPattern("x") => true
      case StatusPattern(other) =>
        throw PlanParseException(
          s"Task '$title' has unrecognised status checkbox '$other'"
        )
    val description = afterStatus.mkString("\n").trim
    if description.isEmpty then
      throw PlanParseException(s"Task '$title' has no prompt body")
    Task(title = Title(title), description = description, completed = completed)

class PlanParseException(message: String) extends RuntimeException(message)
