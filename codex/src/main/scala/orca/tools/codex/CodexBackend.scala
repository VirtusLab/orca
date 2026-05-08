package orca.tools.codex

import orca.{
  Backend,
  Conversation,
  LlmBackend,
  LlmConfig,
  LlmResult,
  OrcaDebug,
  OrcaFlowException,
  SessionId,
  Usage
}
import orca.subprocess.{CliRunner, PipedCliProcess}
import orca.tools.codex.jsonl.{InboundEvent, Item}

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Codex backend. Both headless and interactive paths drive `codex exec --json`
  * over stdio: stdout JSONL is parsed into [[InboundEvent]]s, and the assistant
  * message preceding `turn.completed` becomes the result. See
  * [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]] for the shape of
  * the protocol and the rationale for not using the experimental WebSocket
  * app-server.
  *
  * Interactive sessions wrap the same subprocess in a [[CodexConversation]] so
  * the channel can render events live. Multi-turn happens via
  * `continueInteractive`, which spawns a fresh `codex exec resume <thread_id>`.
  */
class CodexBackend(cli: CliRunner) extends LlmBackend[Backend.Codex.type]:

  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[Backend.Codex.type] =
    invokeHeadless(prompt, config, workDir, resume = None)

  def continueHeadless(
      sessionId: SessionId[Backend.Codex.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[Backend.Codex.type] =
    invokeHeadless(prompt, config, workDir, resume = Some(sessionId))

  def runInteractive(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[Backend.Codex.type] =
    openConversation(
      prompt,
      displayPrompt,
      config,
      workDir,
      resume = None,
      outputSchema
    )

  def continueInteractive(
      sessionId: SessionId[Backend.Codex.type],
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[Backend.Codex.type] =
    openConversation(
      prompt,
      displayPrompt,
      config,
      workDir,
      resume = Some(sessionId),
      // codex exec resume doesn't accept --output-schema; structured
      // validation on resume falls to the prompt template + post-hoc
      // parsing in DefaultLlmCall.
      outputSchema = None
    )

  /** Spawn `codex exec --json` and wrap the process in a live
    * [[CodexConversation]]. Stdin is closed immediately — codex consumes the
    * prompt argv-side and reads stdin only as an appended `<stdin>` block,
    * which we don't use.
    */
  private def openConversation(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[Backend.Codex.type]],
      outputSchema: Option[String]
  ): Conversation[Backend.Codex.type] =
    val finalPrompt = mergeSystemPrompt(config, prompt)
    val schemaFile = writeSchemaIfPresent(outputSchema, workDir)
    val args = resume match
      case Some(sid) => CodexArgs.execResume(sid, finalPrompt, config)
      case None      => CodexArgs.exec(finalPrompt, config, schemaFile, workDir)
    val process = cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    try
      // codex doesn't accept user turns over stdin once the initial
      // prompt has been argv-supplied; close immediately so the
      // child stops waiting on stdin EOF. Same reflex as claude's
      // single-shot stream-json path.
      process.closeStdin()
      new CodexConversation(
        process,
        initialPrompt = displayPrompt,
        outputSchema = outputSchema
      )
    catch
      case e: Exception =>
        process.sendSigInt()
        throw OrcaFlowException(
          s"Failed to open codex session: ${e.getMessage}"
        )

  /** Headless invocation: spawn the subprocess, drain the JSONL stream, and
    * assemble an [[LlmResult]] from the thread id, last agent message, and
    * `turn.completed` usage. Unlike claude, codex's `--json` exec stream is the
    * only output format — no separate batched-JSON shape, so headless and
    * interactive share the parser.
    */
  private def invokeHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[Backend.Codex.type]]
  ): LlmResult[Backend.Codex.type] =
    val finalPrompt = mergeSystemPrompt(config, prompt)
    // codex `exec` doesn't carry an output schema for headless calls
    // — DefaultLlmCall's prompt template already pins the structure;
    // ResponseParser handles validation post-hoc. `exec resume` rejects
    // `--output-schema` in any case.
    val args = resume match
      case Some(sid) => CodexArgs.execResume(sid, finalPrompt, config)
      case None =>
        CodexArgs.exec(finalPrompt, config, outputSchemaFile = None, workDir)
    val process = cli.spawnPiped(args, cwd = workDir, pipeStderr = true)
    try
      process.closeStdin()
      drainHeadless(process) match
        case Right(r)  => r
        case Left(msg) => throw OrcaFlowException(s"codex CLI failed: $msg")
    finally if process.isAlive then process.sendSigInt()

  /** Walk the JSONL stream end-to-end, accumulating the artifacts a headless
    * caller cares about: thread id, last agent message, usage. Returns Left
    * with a diagnostic on any failure path (process exit non-zero, parse error,
    * unexpected EOF).
    */
  private def drainHeadless(
      process: PipedCliProcess
  ): Either[String, LlmResult[Backend.Codex.type]] =
    val stderrBuf: AtomicReference[Vector[String]] =
      AtomicReference(Vector.empty)
    val drainStderr: Runnable = () =>
      try
        process.stderrLines.foreach: line =>
          if isReportableStderr(line) then
            val _ = stderrBuf.updateAndGet(_ :+ line.trim)
      catch
        case _: InterruptedException =>
          // Daemon thread is being shut down; preserve the flag so
          // any caller observing it sees the request.
          Thread.currentThread().interrupt()
        case NonFatal(t) =>
          // Best-effort drain; the main thread doesn't depend on
          // stderr to make progress. Surface in debug so a real bug
          // isn't masked.
          if OrcaDebug.streamTrace || OrcaDebug.enabled then
            System.err.println(
              s"[orca-debug codex-stderr-drain] ${t.getClass.getName}: " +
                s"${Option(t.getMessage).getOrElse("")}"
            )
    val stderrThread = new Thread(drainStderr, "codex-headless-stderr")
    stderrThread.setDaemon(true)
    stderrThread.start()

    val foldResult = foldStdout(process, HeadlessAccumulator.empty)
    val exit = process.waitForExit()
    try stderrThread.join()
    catch
      // Interrupt while joining means the parent is shutting down;
      // restoring the flag is the right thing to do but there's
      // nothing else useful to log here.
      case _: InterruptedException => Thread.currentThread().interrupt()

    foldResult match
      case Left(msg) => Left(msg)
      case Right(acc) =>
        if exit != 0 then
          val stderr = stderrBuf.get()
          val diag =
            if stderr.nonEmpty then stderr.mkString("; ") else "no stderr"
          Left(s"exit $exit: $diag")
        else if !acc.sawTurnCompleted then
          Left("codex exited without a turn.completed event")
        else Right(acc.toLlmResult)

  private def foldStdout(
      process: PipedCliProcess,
      seed: HeadlessAccumulator
  ): Either[String, HeadlessAccumulator] =
    try
      val it = process.stdoutLines
      var acc = seed
      while it.hasNext do
        val line = it.next()
        try acc = acc.absorb(InboundEvent.parse(line))
        catch
          case e: Exception =>
            return Left(
              s"failed to parse codex JSONL line: ${e.getMessage} | line=$line"
            )
      Right(acc)
    catch
      case e: Throwable =>
        Left(s"error draining codex stdout: ${e.getMessage}")

  private def isReportableStderr(line: String): Boolean =
    val trimmed = line.trim
    trimmed.nonEmpty &&
    !trimmed.startsWith("Reading additional input from stdin")

  /** codex `exec` has no `--system-prompt` flag (codex picks up `AGENTS.md`
    * files in the working directory for static instructions). Fold a configured
    * `systemPrompt` into the user prompt as a preamble — a low-tech but
    * predictable substitute.
    */
  private def mergeSystemPrompt(config: LlmConfig, userPrompt: String): String =
    config.systemPrompt match
      case None => userPrompt
      case Some(body) =>
        s"""System guidance:
           |$body
           |
           |User request:
           |$userPrompt""".stripMargin

  private def writeSchemaIfPresent(
      schema: Option[String],
      workDir: os.Path
  ): Option[os.Path] =
    schema.map: body =>
      val file = workDir / ".codex" / "orca-output-schema.json"
      os.write.over(file, body, createFolders = true)
      file

/** Fold state for `drainHeadless` — keeps the JSONL→LlmResult reduction
  * explicit instead of scattered across method-scope vars. `absorb` is the
  * per-event step; `toLlmResult` finalises after the stream is exhausted.
  */
private case class HeadlessAccumulator(
    threadId: String,
    lastMessage: String,
    usage: Usage,
    sawTurnCompleted: Boolean,
    model: Option[String]
):
  def absorb(event: InboundEvent): HeadlessAccumulator = event match
    case InboundEvent.ThreadStarted(id, m) => copy(threadId = id, model = m)
    case InboundEvent.TurnCompleted(u) =>
      copy(usage = u, sawTurnCompleted = true)
    case InboundEvent.ItemCompleted(Item.AgentMessage(_, text)) =>
      copy(lastMessage = text)
    case _ => this

  def toLlmResult: LlmResult[Backend.Codex.type] =
    LlmResult(
      sessionId = SessionId[Backend.Codex.type](threadId),
      output = lastMessage,
      usage = usage,
      model = model
    )

private object HeadlessAccumulator:
  val empty: HeadlessAccumulator =
    HeadlessAccumulator("", "", Usage.empty, sawTurnCompleted = false, None)
