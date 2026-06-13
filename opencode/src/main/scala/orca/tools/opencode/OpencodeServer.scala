package orca.tools.opencode

import orca.OrcaFlowException
import orca.subprocess.CliRunner
import ox.{releaseAfterScope, Ox}

import org.slf4j.LoggerFactory

import java.util.UUID

/** Lifecycle owner for a shared `opencode serve` process (ADR 0014): it spawns
  * the server, reads its base URL, and tears the process down at scope end. The
  * HTTP/SSE client to talk to it is exposed via [[http]] — this class *owns* a
  * client rather than *being* one, keeping process lifecycle separate from the
  * request surface ([[OpencodeHttp]]).
  *
  * The process is spawned the first time [[http]] is forced (so a backend wired
  * but never used starts nothing), and both the process and client are torn
  * down when the enclosing Ox scope ends. A random `OPENCODE_SERVER_PASSWORD`
  * keeps the bound localhost port closed to other processes; `--pure` is *not*
  * passed (`OpencodeArgs.serve`) so the server inherits the user's configured
  * providers.
  */
private[opencode] class OpencodeServer(
    cli: CliRunner,
    workDir: os.Path,
    launcher: OpencodeLauncher = OpencodeLauncher.default,
    httpFor: (String, String) => OpencodeHttp = JavaNetOpencodeHttp.start
)(using Ox):

  // Server lifecycle goes to the per-run trace (/tmp/orca-*.log). The raw
  // `spawn:` line (orca.proc) shows `--port 0`, so log the resolved URL — and a
  // teardown line, since a long-lived shared server starting/stopping silently
  // is otherwise invisible.
  private val log = LoggerFactory.getLogger(classOf[OpencodeServer])

  /** The HTTP/SSE client against this server. Forcing it spawns `opencode serve`
    * exactly once: a `lazy val` gives one spawn under concurrent first use and
    * does not cache a failed start (Scala re-runs the initializer if it threw).
    * This is the load-bearing once-init — `OpencodeBackend`'s AtomicReference
    * only guarantees a single server *instance*; this guarantees a single
    * *spawn*.
    */
  lazy val http: OpencodeHttp = start()

  private def start(): OpencodeHttp =
    val password = UUID.randomUUID.toString
    val process = cli.spawnPiped(
      OpencodeArgs.serve(launcher),
      env = Map("OPENCODE_SERVER_PASSWORD" -> password),
      cwd = workDir
    )
    process.closeStdin()
    // Tree, not single-PID: a launch wrapper (e.g. `ollama launch opencode`)
    // may fork the real serve process, which a PID-only SIGINT would orphan.
    releaseAfterScope(process.sendSigIntTree())
    // serve prints "listening on …" within ~1s of binding; a serve that exits
    // without it surfaces as EOF → the throw below. (A serve that stays alive
    // yet never prints would block here — not observed in practice.)
    val out = process.stdoutLines
    val baseUrl = out
      .flatMap(OpencodeServer.parseBaseUrl)
      .nextOption()
      .getOrElse(
        throw OrcaFlowException("opencode serve did not report a listening URL")
      )
    log.debug("opencode server started, listening on {}", baseUrl)
    // Keep draining stdout — resuming the *same* lazy iterator past the bind
    // line — so the server's log output can't back-fill the pipe and stall it.
    // A daemon thread, not an Ox fork: the drain blocks in a native `readLine`
    // that thread interruption can't cancel, so an Ox fork would hang scope
    // teardown forever waiting to join it (the SIGINT that would unblock it runs
    // only *after* the join). The daemon thread ends when teardown SIGINTs the
    // process (stdout EOF), and never blocks JVM exit regardless.
    val drain = new Thread(() => out.foreach(_ => ()), "opencode-stdout-drain")
    drain.setDaemon(true)
    drain.start()
    val client = httpFor(baseUrl, password)
    releaseAfterScope(client.close()) // runs before the SIGINT (LIFO)
    // Registered last → runs first at teardown, announcing the stop before the
    // client close + SIGINT above.
    releaseAfterScope(log.debug("opencode server at {} stopping", baseUrl))
    client

private[opencode] object OpencodeServer:
  private val ListeningLine = """listening on (https?://\S+)""".r

  /** The base URL from a serve startup line (`opencode server listening on
    * http://127.0.0.1:4096`), or `None`.
    */
  def parseBaseUrl(line: String): Option[String] =
    ListeningLine.findFirstMatchIn(line).map(_.group(1))
