package orca.plan

import orca.{FlowContext, LlmTool, OrcaEvent}

/** A markdown-backed plan persisted to a file (typically `dev.md`) so resuming
  * a flow doesn't re-plan from scratch. The branch name is part of the plan;
  * the runtime checks out / creates that branch before iterating tasks.
  *
  * On-disk format (the renderer round-trips it; the parser is strict because we
  * control the writer):
  *
  * {{{
  * # Plan: <branchName>
  *
  * ## Task: <short-name>
  * Status: [ ]
  *
  * <prompt body, free-form, until the next ## Task or EOF>
  *
  * ## Task: <short-name>
  * Status: [x]
  *
  * <prompt body>
  * }}}
  */
case class ExtendedPlan(branchName: String, tasks: List[Task]):

  /** Mark the named task complete, leaving the others untouched. Returns the
    * same plan if `name` doesn't match any task — the caller decides whether
    * that's an error.
    */
  def markComplete(name: String): ExtendedPlan =
    copy(tasks = tasks.map(t => if t.name == name then t.markComplete else t))

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

  /** Mark `taskName` complete in the plan stored at `file`. Reads the file,
    * applies the change, writes it back. Use after a task is committed so a
    * subsequent run resumes at the next pending task.
    */
  def persistComplete(file: os.Path, taskName: String): Unit =
    val current = parse(os.read(file))
    val updated = current.markComplete(taskName)
    os.write.over(file, render(updated))

  /** The schema description we hand to the planner LLM so it generates plans we
    * can parse. Kept as a constant so flows can reference it without copying
    * the format definition.
    */
  val SchemaDescription: String =
    """A development plan is a markdown document with this exact
      |structure:
      |
      |    # Plan: <branchName>
      |
      |    ## Task: <short kebab-case name>
      |    Status: [ ]
      |
      |    <prompt body — the instruction the implementor agent
      |    receives for this task. Free-form prose, may span
      |    multiple paragraphs and use markdown.>
      |
      |    ## Task: <short kebab-case name>
      |    Status: [ ]
      |
      |    <prompt body>
      |
      |Rules:
      |  - The first H1 must be `# Plan: <branchName>`. The branch
      |    name is lowercase kebab-case, no spaces.
      |  - Each task is an H2 of the form `## Task: <name>` with
      |    one short kebab-case identifier (max ~30 chars).
      |  - Each task's first body line is `Status: [ ]` (pending) or
      |    `Status: [x]` (done). When generating a fresh plan, set
      |    every task to `[ ]`.
      |  - The prompt body follows the Status line, separated by a
      |    blank line. It runs until the next `## Task:` heading or
      |    end of file.
      |""".stripMargin

  /** Parse a plan from its markdown representation. Strict — throws
    * [[PlanParseException]] on any deviation from the schema. We write these
    * files ourselves (or have the LLM write them under a strict schema prompt)
    * so format drift is a real bug, not something to silently absorb.
    *
    * CRLF line endings and a leading BOM are normalised first so editor-saved
    * or LLM-emitted files don't fail with confusing "expected `# Plan:` got ?"
    * diagnostics over a single invisible codepoint.
    */
  def parse(markdown: String): ExtendedPlan =
    val normalised = markdown.stripPrefix("﻿").replace("\r\n", "\n")
    val lines = normalised.linesIterator.toList
    val branchName = parseHeader(lines)
    val taskBlocks = splitTaskBlocks(lines)
    if taskBlocks.isEmpty then throw PlanParseException("Plan has no tasks")
    ExtendedPlan(branchName, taskBlocks.map(parseTask))

  /** Render a plan back into the on-disk format. Output round-trips through
    * [[parse]] without information loss; we use this to write the file when
    * first generated and again when a task's status flips.
    */
  def render(plan: ExtendedPlan): String =
    val header = s"# Plan: ${plan.branchName}\n"
    val body = plan.tasks
      .map: t =>
        val checkbox = if t.completed then "[x]" else "[ ]"
        s"\n## Task: ${t.name}\nStatus: $checkbox\n\n${t.description.stripLineEnd}\n"
      .mkString
    header + body

  // --- Parser internals ---

  private val HeaderPattern = "^# Plan:\\s*(\\S.*)$".r
  private val TaskHeaderPattern = "^## Task:\\s*(\\S.*)$".r
  private val StatusPattern = "^Status:\\s*\\[(.)\\]\\s*$".r

  private def parseHeader(lines: List[String]): String =
    lines.find(_.trim.nonEmpty) match
      case Some(HeaderPattern(branch)) => branch.trim
      case other =>
        throw PlanParseException(
          s"Expected first non-blank line to match `# Plan: <branch>`; got: ${other.getOrElse("(empty file)")}"
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
    val name = block.headOption match
      case Some(TaskHeaderPattern(n)) => n.trim
      case _ =>
        throw PlanParseException(
          s"Task block doesn't start with `## Task: <name>`: ${block.headOption.getOrElse("")}"
        )
    val rest = block.tail.dropWhile(_.trim.isEmpty)
    val (statusLine, afterStatus) = rest.headOption match
      case Some(line @ StatusPattern(_)) => (line, rest.tail)
      case _ =>
        throw PlanParseException(
          s"Task '$name' is missing a `Status: [ ]` / `Status: [x]` line"
        )
    val completed = statusLine match
      case StatusPattern(" ") => false
      case StatusPattern("x") => true
      case StatusPattern(other) =>
        throw PlanParseException(
          s"Task '$name' has unrecognised status checkbox '$other'"
        )
    val description = afterStatus.mkString("\n").trim
    if description.isEmpty then
      throw PlanParseException(s"Task '$name' has no prompt body")
    Task(
      name = name,
      shortSummary = humaniseName(name),
      description = description,
      completed = completed
    )

  /** Turn a kebab-case task name into a human-readable label so the
    * shortSummary slot has something useful for display. The on-disk markdown
    * format doesn't carry an explicit summary, so this is the best we can do
    * without changing the schema.
    */
  private def humaniseName(name: String): String =
    name.split("-").toList match
      case Nil          => name
      case head :: tail => (head.capitalize :: tail).mkString(" ")

class PlanParseException(message: String) extends RuntimeException(message)
