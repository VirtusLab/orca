package orca.plan

import orca.{FlowContext, LlmTool, OrcaEvent, Title}

/** A markdown-backed plan persisted to a file (typically `epic.md`) so resuming
  * a flow doesn't re-plan from scratch. `epicId` is the kebab-case identifier
  * the runtime uses as the git branch name.
  *
  * On-disk format (the renderer round-trips it; the parser is strict because we
  * control the writer):
  *
  * {{{
  * # Plan: <epicId>
  *
  * ## Task: <title>
  * Status: [ ]
  *
  * <prompt body, free-form, until the next ## Task or EOF>
  *
  * ## Task: <title>
  * Status: [x]
  *
  * <prompt body>
  * }}}
  */
case class ExtendedPlan(epicId: String, tasks: List[Task]):

  /** Mark the task with the given `title` complete, leaving the others
    * untouched. Returns the same plan if no task matches.
    */
  def markComplete(title: Title): ExtendedPlan =
    copy(tasks = tasks.map(t => if t.title == title then t.markComplete else t))

  /** First task whose `completed` flag is false, in declaration order. None
    * means the plan is fully done.
    */
  def firstIncomplete: Option[Task] = tasks.find(!_.completed)

object ExtendedPlan:

  /** Idempotent plan acquisition. If `file` already exists, parse and return it
    * (and log a Step explaining we're reusing it). Otherwise, ask `llm` to
    * produce a plan in the schema, write it to `file`, and return the parsed
    * result.
    *
    * The reuse path is the resume contract: a flow that crashed mid-loop can be
    * re-run without re-planning from scratch and without losing the per-task
    * `[x]` progress markers the previous run committed.
    *
    * Parse failures throw [[PlanParseException]] — the caller decides whether
    * to delete the file and retry or surface the error.
    */
  def loadOrGenerate(
      file: os.Path,
      userPrompt: String,
      llm: LlmTool[?]
  )(using ctx: FlowContext): ExtendedPlan =
    if os.exists(file) then
      val parsed = parse(os.read(file))
      ctx.emit(
        OrcaEvent.Step(
          s"Reusing existing plan at $file (${parsed.tasks.size} task(s), ${parsed.tasks.count(_.completed)} already complete)"
        )
      )
      parsed
    else
      val markdown = llm.ask(
        s"""$userPrompt
           |
           |Produce a development plan in the markdown format below.
           |Reply with ONLY the markdown — no surrounding prose, no
           |code fences.
           |
           |$SchemaDescription""".stripMargin
      )
      val parsed = parse(markdown.trim)
      os.write.over(file, render(parsed), createFolders = true)
      parsed

  /** Mark the task with `title` complete in the plan stored at `file`. Reads
    * the file, applies the change, writes it back. Use after a task is
    * committed so a subsequent run resumes at the next pending task.
    */
  def persistComplete(file: os.Path, title: Title): Unit =
    val current = parse(os.read(file))
    val updated = current.markComplete(title)
    os.write.over(file, render(updated))

  /** The schema description we hand to the planner LLM so it generates plans we
    * can parse. Kept as a constant so flows can reference it without copying
    * the format definition.
    */
  val SchemaDescription: String =
    """A development plan is a markdown document with this exact
      |structure:
      |
      |    # Plan: <epicId>
      |
      |    ## Task: <title>
      |    Status: [ ]
      |
      |    <prompt body — the instruction the implementor agent
      |    receives for this task. Free-form prose, may span
      |    multiple paragraphs and use markdown.>
      |
      |    ## Task: <title>
      |    Status: [ ]
      |
      |    <prompt body>
      |
      |Rules:
      |  - The first H1 must be `# Plan: <epicId>`. The epic id is
      |    lowercase kebab-case, no spaces — used as the git branch
      |    name for the whole plan.
      |  - Each task is an H2 of the form `## Task: <title>` where
      |    `title` is a short human-readable label.
      |  - Each task's first body line is `Status: [ ]` (pending) or
      |    `Status: [x]` (done). When generating a fresh plan, set
      |    every task to `[ ]`.
      |  - The prompt body follows the Status line, separated by a
      |    blank line. It runs until the next `## Task:` heading or
      |    end of file.
      |""".stripMargin

  /** Parse a plan from its markdown representation. Strict — throws
    * [[PlanParseException]] on any deviation from the schema. CRLF line endings
    * and a leading BOM are normalised first.
    */
  def parse(markdown: String): ExtendedPlan =
    val normalised = markdown.stripPrefix("﻿").replace("\r\n", "\n")
    val lines = normalised.linesIterator.toList
    val epicId = parseHeader(lines)
    val taskBlocks = splitTaskBlocks(lines)
    if taskBlocks.isEmpty then throw PlanParseException("Plan has no tasks")
    ExtendedPlan(epicId, taskBlocks.map(parseTask))

  /** Render a plan back into the on-disk format. Output round-trips through
    * [[parse]] without information loss; we use this to write the file when
    * first generated and again when a task's status flips.
    */
  def render(plan: ExtendedPlan): String =
    val header = s"# Plan: ${plan.epicId}\n"
    val body = plan.tasks
      .map: t =>
        val checkbox = if t.completed then "[x]" else "[ ]"
        s"\n## Task: ${t.title}\nStatus: $checkbox\n\n${t.description.stripLineEnd}\n"
      .mkString
    header + body

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
