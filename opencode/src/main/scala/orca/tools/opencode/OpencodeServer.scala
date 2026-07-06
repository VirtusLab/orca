package orca.tools.opencode

import orca.OrcaFlowException
import orca.subprocess.{CliRunner, PipedCliProcess}
import ox.{fork, forkDiscard, Ox}

import org.slf4j.LoggerFactory

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Lifecycle owner for a shared `opencode serve` process (ADR 0014): it spawns
  * the server, reads its base URL, and tears the process down via [[shutdown]].
  * The HTTP/SSE client to talk to it is exposed via [[http]] — this class
  * *owns* a client rather than *being* one, keeping process lifecycle separate
  * from the request surface ([[OpencodeHttp]]).
  *
  * The process is spawned the first time [[http]] is forced (so a backend wired
  * but never used starts nothing). Its stdout/stderr are drained by Ox forks
  * bound to the enclosing (flow) scope; [[shutdown]] destroys the process —
  * which unblocks those forks' non-interruptible reads — so the scope can then
  * join them cleanly. `shutdown` MUST be called from the flow body's `finally`
  * (before the scope joins its forks): Ox runs `releaseAfterScope` finalizers
  * *after* the join, so a `releaseAfterScope`-based kill would deadlock on a
  * fork blocked in `readLine`. The runner wires this via
  * `DefaultFlowContext.close`.
  *
  * A random `OPENCODE_SERVER_PASSWORD` keeps the bound localhost port closed to
  * other processes; `--pure` is *not* passed (`OpencodeArgs.serve`) so the
  * server inherits the user's configured providers.
  *
  * The internal `processRef`/`clientRef`/`stopped` atomics are deliberately not
  * a single monitor: [[shutdown]] must be able to run while [[start]] is
  * blocked in a non-interruptible native read, and destroying the process is
  * precisely what unblocks that read — a shared lock would deadlock there.
  */
private[opencode] class OpencodeServer(
    cli: CliRunner,
    workDir: os.Path,
    launcher: OpencodeLauncher = OpencodeLauncher.default,
    httpFor: (String, String) => OpencodeHttp = JavaNetOpencodeHttp.start
)(using Ox)
    extends OpencodeServerHandle:

  private val log = LoggerFactory.getLogger(classOf[OpencodeServer])

  // Set during start() so shutdown() can tear them down. The reader forks block
  // on a non-interruptible read, so destroying the process is the only way to
  // unblock them — see the class scaladoc.
  private val processRef = new AtomicReference[PipedCliProcess]()
  private val clientRef = new AtomicReference[OpencodeHttp]()
  private val stopped = new AtomicBoolean(false)

  /** The HTTP/SSE client against this server. Forcing it spawns `opencode
    * serve` exactly once: a `lazy val` gives one spawn under concurrent first
    * use and does not cache a failed start (Scala re-runs the initializer if it
    * threw). This is the load-bearing once-init — `OpencodeBackend` holds a
    * single server *instance* (built eagerly at construction); this `lazy val`
    * guarantees a single *spawn* of it.
    */
  lazy val http: OpencodeHttp = start()

  /** Whether the server has spawned (its client is recorded). Reading it never
    * forces [[http]], so a probe can answer "absent" without a spawn.
    */
  def started: Boolean = clientRef.get() != null

  /** Tear down the server: tree-destroy the process (unblocking the drain
    * forks' reads so the enclosing scope can join them) and close the HTTP
    * client. Idempotent and a no-op if the server was never started. Must run
    * in the flow body's `finally`, before the scope joins the drain forks (see
    * class scaladoc).
    *
    * In the runner's normal flow `http` is forced during the body and
    * `shutdown` runs in the same scope's `finally` afterwards, so the process
    * is already recorded here. Should a background fork ever force `http`
    * concurrently with this call and lose the `processRef` write/read race,
    * `start` re-checks `stopped` after spawning and tree-destroys the process
    * itself — so the kill is never silently missed.
    */
  def shutdown(): Unit =
    if stopped.compareAndSet(false, true) then
      val proc = Option(processRef.get())
      if proc.isDefined then log.debug("opencode server stopping")
      // Tree-destroy (not SIGINT, not PID-only) so EVERY pipe holder dies and
      // the drains' native reads hit EOF before the enclosing scope joins them —
      // a launch wrapper (ollama) forks the real serve, which inherits the pipes.
      proc.foreach(_.destroyForciblyTree())
      Option(clientRef.get()).foreach(_.close())

  /** [[OpencodeServerHandle.close]] — the handle's idempotent teardown, aliased
    * to [[shutdown]].
    */
  def close(): Unit = shutdown()

  private def start(): OpencodeHttp =
    val password = UUID.randomUUID.toString
    // Pipe stderr (don't inherit it): a failed launch — e.g. `ollama launch`
    // reporting a missing model — writes the reason here, so we can put it in
    // the start-failure error below instead of losing it to the console.
    val process = cli.spawnPiped(
      OpencodeArgs.serve(launcher),
      env = Map("OPENCODE_SERVER_PASSWORD" -> password),
      cwd = workDir,
      pipeStderr = true
    )
    processRef.set(process)
    process.closeStdin()
    // Drain stderr in a fork (a chatty launcher mustn't fill the pipe and stall
    // startup), tracing each line and keeping a bounded tail to report if the
    // server never binds. A joinable `fork` (not `forkDiscard`) so the bind-
    // failure path can wait for the tail; the body swallows NonFatal so a stray
    // read error never tears down the flow scope.
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
        // Tree-destroy first so the stderr fork's read EOFs (even if a wrapper
        // forked a pipe-holding child) and the join below can't hang, then
        // surface its stderr (e.g. ollama's "model not found").
        process.destroyForciblyTree()
        errFork.join()
        val tail = String.join("\n", errTail)
        throw OrcaFlowException(
          "opencode serve did not start" +
            (if tail.nonEmpty then s":\n$tail"
             else " and produced no error output")
        )
    log.debug("opencode server started, listening on {}", baseUrl)
    // Keep draining stdout — resuming the *same* lazy iterator past the bind
    // line — so the server's log output can't back-fill the pipe and stall it.
    // A `forkDiscard` in the flow scope; `shutdown`'s destroy unblocks its read
    // before the scope joins it (the read is native and interrupt-immune).
    forkDiscard:
      try out.foreach(_ => ())
      catch case NonFatal(e) => log.debug("opencode stdout drain ended", e)
    // Close the shutdown-before-processRef window structurally: if `shutdown`
    // latched `stopped` before `processRef` was set, it destroyed nothing — do
    // it here so the drains we just forked don't outlive that shutdown.
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
