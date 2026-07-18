package orca.tools.opencode

import orca.OrcaFlowException
import orca.subprocess.{CliRunner, PipedCliProcess}
import ox.{fork, forkDiscard, Ox}

import org.slf4j.LoggerFactory

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Lifecycle owner for a shared `opencode serve` process (ADR 0014): spawns the
  * server, reads its base URL, and tears it down via [[shutdown]]. Owns the
  * HTTP/SSE client ([[OpencodeHttp]]) rather than being one.
  *
  * The process is spawned the first time [[http]] is forced (so a backend wired
  * but never used starts nothing). Its stdout/stderr are drained by Ox forks in
  * the enclosing flow scope; [[shutdown]] destroys the process to unblock those
  * forks' non-interruptible reads so the scope can join them. `shutdown` MUST
  * run in the flow body's `finally`, before the scope joins its forks: Ox runs
  * `releaseAfterScope` finalizers *after* the join, so a `releaseAfterScope`
  * kill would deadlock on a fork blocked in `readLine`.
  *
  * A random `OPENCODE_SERVER_PASSWORD` keeps the bound localhost port closed to
  * other processes; `--pure` is not passed so the server inherits the user's
  * configured providers.
  *
  * The `processRef`/`clientRef`/`stopped` atomics are deliberately not a single
  * monitor: [[shutdown]] must run while [[start]] is blocked in a
  * non-interruptible native read, and destroying the process is what unblocks
  * that read — a shared lock would deadlock.
  *
  * The process is per-run and ephemeral, but opencode's session storage is not:
  * it persists sessions to a global on-disk store shared by every `opencode
  * serve` on the machine, so a fresh process resumes a session minted by a
  * prior one. Sessions are thus durable
  * ([[orca.backend.SessionSupport.durable]]) across a restart;
  * [[OpencodeBackend.probeSession]] just forces a fresh spawn to see it.
  */
private[opencode] class OpencodeServer(
    cli: CliRunner,
    workDir: os.Path,
    launcher: OpencodeLauncher = OpencodeLauncher.default,
    httpFor: (String, String) => OpencodeHttp = JavaNetOpencodeHttp.start
)(using Ox)
    extends OpencodeServerHandle:

  private val log = LoggerFactory.getLogger(classOf[OpencodeServer])

  // Set during start() so shutdown() can tear them down.
  private val processRef = new AtomicReference[PipedCliProcess]()
  private val clientRef = new AtomicReference[OpencodeHttp]()
  private val stopped = new AtomicBoolean(false)

  /** The HTTP/SSE client against this server. Forcing it spawns `opencode
    * serve` exactly once; a failed start is not cached (the initializer re-runs
    * on the next force).
    */
  lazy val http: OpencodeHttp = start()

  /** Tear down the server: tree-destroy the process (unblocking the drain
    * forks' reads so the scope can join them) and close the HTTP client.
    * Idempotent and a no-op if the server was never started. Must run in the
    * flow body's `finally`, before the scope joins the drain forks.
    *
    * If a fork forces `http` concurrently and loses the `processRef` race,
    * `start` re-checks `stopped` after spawning and tree-destroys the process
    * itself, so the kill is never missed.
    */
  def shutdown(): Unit =
    if stopped.compareAndSet(false, true) then
      val proc = Option(processRef.get())
      if proc.isDefined then log.debug("opencode server stopping")
      // Tree-destroy (not PID-only) so EVERY pipe holder dies and the drains'
      // reads hit EOF: a launch wrapper (ollama) forks the real serve, which
      // inherits the pipes.
      proc.foreach(_.destroyForciblyTree())
      Option(clientRef.get()).foreach(_.close())

  /** [[OpencodeServerHandle.close]] — the handle's idempotent teardown, aliased
    * to [[shutdown]].
    */
  def close(): Unit = shutdown()

  private def start(): OpencodeHttp =
    val password = UUID.randomUUID.toString
    // Pipe stderr (don't inherit): a failed launch (e.g. `ollama launch`
    // reporting a missing model) writes the reason here for the start-failure
    // error below.
    val process = cli.spawnPiped(
      OpencodeArgs.serve(launcher),
      env = Map("OPENCODE_SERVER_PASSWORD" -> password),
      cwd = workDir,
      pipeStderr = true
    )
    processRef.set(process)
    process.closeStdin()
    // Drain stderr in a fork (a chatty launcher mustn't fill the pipe and stall
    // startup), keeping a bounded tail to report if the server never binds. A
    // joinable `fork` so the bind-failure path can wait for the tail.
    val errTail = new ConcurrentLinkedDeque[String]()
    val errFork = fork:
      try
        process.stderrLines.foreach: line =>
          log.debug("opencode serve stderr: {}", line)
          errTail.addLast(line)
          while errTail.size > OpencodeServer.MaxErrTailLines do
            val _ = errTail.poll()
      catch case NonFatal(e) => log.debug("opencode stderr drain ended", e)
    // serve prints "listening on …" within ~1s of binding; a serve that exits
    // without it surfaces as EOF here.
    val out = process.stdoutLines
    val baseUrl = out
      .flatMap(OpencodeServer.parseBaseUrl)
      .nextOption()
      .getOrElse:
        // stdout closed with no listening line — the launcher/serve exited.
        // Tree-destroy first so the stderr fork's read EOFs and the join can't
        // hang, then surface its stderr (e.g. ollama's "model not found").
        process.destroyForciblyTree()
        errFork.join()
        val tail = String.join("\n", errTail)
        throw OrcaFlowException(
          "opencode serve did not start" +
            (if tail.nonEmpty then s":\n$tail"
             else " and produced no error output")
        )
    log.debug("opencode server started, listening on {}", baseUrl)
    // Keep draining stdout (resuming the same iterator past the bind line) so
    // the server's log output can't back-fill the pipe and stall it.
    // `shutdown`'s destroy unblocks this fork's interrupt-immune read.
    forkDiscard:
      try out.foreach(_ => ())
      catch case NonFatal(e) => log.debug("opencode stdout drain ended", e)
    // If `shutdown` latched `stopped` before `processRef` was set, it destroyed
    // nothing — do it here so the drains just forked don't outlive it.
    if stopped.get() then process.destroyForciblyTree()
    val client = httpFor(baseUrl, password)
    clientRef.set(client)
    client

private[opencode] object OpencodeServer:
  private val ListeningLine = """listening on (https?://\S+)""".r

  /** Cap on stderr lines kept for a start-failure message. */
  private val MaxErrTailLines = 50

  /** The base URL from a serve startup line (`opencode server listening on
    * http://127.0.0.1:4096`), or `None`.
    */
  def parseBaseUrl(line: String): Option[String] =
    ListeningLine.findFirstMatchIn(line).map(_.group(1))
