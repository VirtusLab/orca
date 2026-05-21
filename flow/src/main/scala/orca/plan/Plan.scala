package orca.plan

import orca.{FlowContext}
import orca.plan.Title
import orca.llm.{
  Announce,
  BackendTag,
  CanAskUser,
  JsonData,
  LlmTool,
  SessionId,
  given
}
import orca.events.OrcaEvent

/** A development plan: an ordered list of [[Task]]s the agent will work
  * through, all on a single branch named by `epicId` (kebab-case, used directly
  * as the git branch name).
  *
  * The same type covers two usage shapes:
  *   - **In-memory** — `Plan.interactive.from` / `Plan.autonomous.from` return
  *     one. The flow iterates `tasks` and forgets the plan when it exits.
  *   - **Markdown-backed** — `Plan.interactive.loadOrGenerate` /
  *     `Plan.autonomous.loadOrGenerate`, plus `Plan.persistComplete`,
  *     round-trip the plan through a `## Task: …` markdown file so a flow that
  *     crashes mid-loop can resume without re-planning. The on-disk format is
  *     parsed/rendered by [[Plan.parse]] / [[Plan.render]].
  *
  * Whether the planning round-trip lets the agent ask clarifying questions
  * (`interactive`) or runs as a single agentic turn with no human in the loop
  * (`autonomous`) is chosen by selecting the matching nested object — visible
  * at the call site rather than hidden behind a parameter default.
  *
  * `derives JsonData` so the structured-output path works directly: the helper
  * methods consume Orca's auto-generated JSON schema; no caller-side
  * serialization is needed.
  */
case class Plan(
    epicId: String,
    description: String,
    tasks: List[Task]
) derives JsonData:

  /** Mark the task with the given `title` complete, leaving the others
    * untouched. Returns the same plan if no task matches.
    */
  def markComplete(title: Title): Plan =
    copy(tasks = tasks.map(t => if t.title == title then t.markComplete else t))

  /** First task whose `completed` flag is false, in declaration order. `None`
    * means the plan is fully done.
    */
  def firstIncomplete: Option[Task] = tasks.find(!_.completed)

object Plan:

  /** Interactive planning helpers — the LLM call opens a conversation the user
    * can drive (clarifying questions, refinements) before producing the plan.
    * `from` returns `(SessionId, Plan)` so the caller can `continueSession` for
    * the implementation turns. `loadOrGenerate` returns just `Plan` —
    * persistence rather than session continuity is the use case it serves; if
    * you need the planning conversation alive, use `from` and write the
    * markdown yourself with `Plan.render`.
    *
    * The `B: CanAskUser` constraint means these helpers compile only with
    * backends that can host an `ask_user` tool — today, Claude. Calling
    * `Plan.interactive.from(prompt, codex)` is a compile error rather than a
    * silent degradation. Use `Plan.autonomous.*` if you don't need mid-session
    * questions.
    */
  object interactive:
    def from[B <: BackendTag: CanAskUser](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): (SessionId[B], Plan) =
      llm
        .resultAs[Plan]
        .interactive
        .startSession(s"$userPrompt\n\n$instructions")

    def loadOrGenerate[B <: BackendTag: CanAskUser](
        file: os.Path,
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Plan =
      loadOrGenerateImpl(
        file,
        () =>
          llm
            .resultAs[Plan]
            .interactive
            .startSession(s"$userPrompt\n\n$instructions")
            ._2
      )

  /** Autonomous planning helpers — a single agentic turn, no human in the loop.
    * Sibling of [[interactive]]; the choice between the two is visible at the
    * call site (`Plan.autonomous.from(...)` vs `Plan.interactive.from(...)`).
    */
  object autonomous:
    def from[B <: BackendTag](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): (SessionId[B], Plan) =
      llm
        .resultAs[Plan]
        .autonomous
        .startSession(s"$userPrompt\n\n$instructions")

    def loadOrGenerate(
        file: os.Path,
        userPrompt: String,
        llm: LlmTool[?],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Plan =
      loadOrGenerateImpl(
        file,
        () => llm.resultAs[Plan].autonomous.run(s"$userPrompt\n\n$instructions")
      )

  /** Common load-or-generate body: read+log on resume, otherwise call
    * `generate` and persist its result. The two public variants differ only in
    * which LLM-call shape they pass for `generate` (interactive vs autonomous).
    */
  private def loadOrGenerateImpl(file: os.Path, generate: () => Plan)(using
      ctx: FlowContext
  ): Plan =
    if os.exists(file) then
      val parsed = parse(os.read(file))
      ctx.emit(
        OrcaEvent.Step(
          s"Reusing existing plan at $file (${parsed.tasks.size} task(s), ${parsed.tasks.count(_.completed)} already complete)"
        )
      )
      parsed
    else
      val plan = generate()
      os.write.over(file, render(plan), createFolders = true)
      plan

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

  /** Mark the task with `title` complete in the plan stored at `file`. Reads
    * the file, applies the change, writes it back. Use after a task is
    * committed so a subsequent run resumes at the next pending task.
    */
  def persistComplete(file: os.Path, title: Title): Unit =
    val current = parse(os.read(file))
    val updated = current.markComplete(title)
    os.write.over(file, render(updated))

  /** Parse a plan from its markdown representation. Strict — throws
    * [[PlanParseException]] on any deviation from the schema. CRLF line endings
    * and a leading BOM are normalised first.
    */
  def parse(markdown: String): Plan =
    val normalised = markdown.stripPrefix("﻿").replace("\r\n", "\n")
    val lines = normalised.linesIterator.toList
    val epicId = parseHeader(lines)
    val description = parseDescription(lines)
    val taskBlocks = splitTaskBlocks(lines)
    if taskBlocks.isEmpty then throw PlanParseException("Plan has no tasks")
    Plan(epicId, description, taskBlocks.map(parseTask))

  /** Render a plan back into the on-disk format. Output round-trips through
    * [[parse]] without information loss; we use this to write the file when
    * first generated and again when a task's status flips.
    */
  def render(plan: Plan): String =
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
