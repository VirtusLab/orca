package orca.tools.opencode

import orca.OrcaFlowException
import orca.backend.StreamSource
import orca.subprocess.CliRunner
import ox.{releaseAfterScope, Ox}

import java.util.UUID

/** A shared, lazily-started `opencode serve` plus the HTTP/SSE client against
  * it (ADR 0014).
  *
  * The process is spawned on the first call (so a backend wired but never used
  * starts nothing), its base URL read from the startup line, and both the
  * process and client are torn down when the enclosing Ox scope ends. A random
  * `OPENCODE_SERVER_PASSWORD` keeps the bound localhost port closed to other
  * processes; `--pure` is *not* passed (`OpencodeArgs.serve`) so the server
  * inherits the user's configured providers.
  */
private[opencode] class OpencodeServer(
    cli: CliRunner,
    workDir: os.Path,
    httpFor: (String, String) => OpencodeHttp = JavaNetOpencodeHttp.start
)(using Ox)
    extends OpencodeHttp:

  // A `lazy val` gives exactly one spawn under concurrent first use, and does
  // not cache a failed start (Scala re-runs the initializer if it threw). This
  // is the load-bearing once-init: `OpencodeBackend`'s AtomicReference only
  // guarantees a single server *instance*, this guarantees a single *spawn*.
  private lazy val http: OpencodeHttp = start()

  def postJson(path: String, body: String): String = http.postJson(path, body)

  /** A fresh SSE stream per turn. Its lifetime is the conversation's, not the
    * server's — the conversation interrupts it at turn end (and the backend
    * releases it as a backstop), so it is deliberately **not** registered on
    * this scope, which would retain one finalizer per turn for the server's
    * whole life.
    */
  def events(): StreamSource = http.events()

  private def start(): OpencodeHttp =
    val password = UUID.randomUUID.toString
    val process = cli.spawnPiped(
      OpencodeArgs.serve(),
      env = Map("OPENCODE_SERVER_PASSWORD" -> password),
      cwd = workDir
    )
    process.closeStdin()
    releaseAfterScope(process.sendSigInt())
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
    client

private[opencode] object OpencodeServer:
  private val ListeningLine = """listening on (https?://\S+)""".r

  /** The base URL from a serve startup line (`opencode server listening on
    * http://127.0.0.1:4096`), or `None`.
    */
  def parseBaseUrl(line: String): Option[String] =
    ListeningLine.findFirstMatchIn(line).map(_.group(1))
