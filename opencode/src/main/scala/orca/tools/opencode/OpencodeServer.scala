package orca.tools.opencode

import orca.OrcaFlowException
import orca.events.OrcaListener
import orca.subprocess.{CliRunner, PipedCliProcess}
import orca.sweep.EnvCookieSweep
import ox.{
  Fork,
  Ox,
  fork,
  forkDiscard,
  forever,
  releaseAfterScope,
  supervised,
  useInScope
}
import ox.channels.{Channel, Sink, Source}
import ox.either.{catching, orThrow}

import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.control.NonFatal

/** A run's shared `opencode serve` process (ADR 0014), started on the first
  * [[http]] call, so a backend wired but never used spawns nothing.
  *
  * The process, its output drains and its HTTP client live in one daemon fork
  * of the scope [[OpencodeServer.start]] was called in, and are torn down when
  * that scope ends; when the process is gone, its environment cookie is swept.
  * A survivor found then is logged, but may not reach a terminal that has
  * already closed.
  *
  * A random `OPENCODE_SERVER_PASSWORD` keeps the bound localhost port closed to
  * other processes; `--pure` is not passed so the server inherits the user's
  * configured providers.
  *
  * The process is per-run and ephemeral, but opencode's session storage is not:
  * it persists sessions to a global on-disk store shared by every `opencode
  * serve` on the machine, so a fresh process resumes a session minted by a
  * prior one. Sessions are thus durable
  * ([[orca.backend.SessionSupport.durable]]) across a restart;
  * [[OpencodeBackend.probeSession]] just forces a fresh spawn to see it.
  */
private[opencode] final class OpencodeServer private (
    requests: Sink[OpencodeServer.Reply]
) extends OpencodeServerHandle:

  /** The HTTP/SSE client against the running server, starting it on the first
    * call. A failed start is rethrown here and retried on the next call. Throws
    * `ChannelClosedException` once the owning scope has ended.
    */
  def http: OpencodeHttp =
    val reply = Channel.buffered[Either[Throwable, OpencodeHttp]](1)
    requests.send(reply)
    reply.receive().orThrow

private[opencode] object OpencodeServer:

  /** Where the server answers one [[OpencodeServer.http]] call. */
  private type Reply = Sink[Either[Throwable, OpencodeHttp]]

  private val log = LoggerFactory.getLogger(classOf[OpencodeServer])

  private val ListeningLine = """listening on (https?://\S+)""".r

  /** Cap on stderr lines kept for a start-failure message. */
  private val MaxErrTailLines = 50

  /** A server owned by the current scope: the process is spawned on the first
    * [[OpencodeServer.http]] call and destroyed when the scope ends. Leaked
    * work still carrying its environment cookie is reported to `events`.
    */
  def start(
      cli: CliRunner,
      workDir: os.Path,
      events: OrcaListener,
      launcher: OpencodeLauncher = OpencodeLauncher.default,
      httpFor: (String, String) => OpencodeHttp = JavaNetOpencodeHttp.start
  )(using Ox): OpencodeServer =
    val requests = Channel.rendezvous[Reply]
    val owner = new ServerOwner(cli, workDir, events, launcher, httpFor)
    forkDiscard:
      try owner.serve(requests)
      finally requests.done()
    new OpencodeServer(requests)

  /** The base URL from a serve startup line (`opencode server listening on
    * http://127.0.0.1:4096`), or `None`.
    */
  def parseBaseUrl(line: String): Option[String] =
    ListeningLine.findFirstMatchIn(line).map(_.group(1))

  /** Runs one server at a time for the requests it receives. */
  private class ServerOwner(
      cli: CliRunner,
      workDir: os.Path,
      events: OrcaListener,
      launcher: OpencodeLauncher,
      httpFor: (String, String) => OpencodeHttp
  ):

    /** Nothing is spawned until the first request. A start that fails answers
      * that request only, so the next one starts afresh.
      */
    def serve(requests: Source[Reply]): Nothing =
      forever:
        val first = requests.receive()
        supervised(runServer(first, requests))

    private def runServer(first: Reply, requests: Source[Reply])(using
        Ox
    ): Unit =
      val password = UUID.randomUUID.toString
      spawn(password).catching[Throwable] match
        case Left(e) => first.send(Left(e))
        case Right(process) =>
          releaseAfterScope(
            EnvCookieSweep.afterTurn(process.envCookie, events)
          )
          // The drains block in pipe reads that interruption can't end:
          // destroying the process here, before the scope joins them, EOFs
          // those reads. Tree-destroy, as a launch wrapper (ollama) forks the
          // real serve, which inherits the pipes.
          try
            val started = connect(process, password).catching[Throwable]
            first.send(started)
            started.foreach: client =>
              forever(requests.receive().send(Right(client)))
          finally process.destroyForciblyTree()

    // Pipe stderr (don't inherit): a failed launch (e.g. `ollama launch`
    // reporting a missing model) writes the reason there, for the error below.
    private def spawn(password: String): PipedCliProcess =
      cli.spawnPiped(
        OpencodeArgs.serve(launcher),
        env = Map("OPENCODE_SERVER_PASSWORD" -> password),
        cwd = workDir,
        pipeStderr = true
      )

    /** Waits for the server to bind and returns a client for it, keeping its
      * output drained for the rest of the scope.
      */
    private def connect(process: PipedCliProcess, password: String)(using
        Ox
    ): OpencodeHttp =
      process.closeStdin()
      val errTail = fork(stderrTail(process))
      val out = process.stdoutLines
      // Read in a fork, so a scope ending mid-startup interrupts the join and
      // reaches the process destroy. serve prints "listening on …" within ~1s
      // of binding; a serve that exits without it surfaces as EOF.
      val baseUrl =
        fork(out.flatMap(parseBaseUrl).nextOption().catching[Throwable])
          .join()
          .orThrow
          .getOrElse(failedToStart(process, errTail))
      log.debug("opencode server started, listening on {}", baseUrl)
      // Resumes the same iterator past the bind line, so the server's log
      // output can't back-fill the pipe and stall it.
      forkDiscard:
        try out.foreach(_ => ())
        catch case NonFatal(e) => log.debug("opencode stdout drain ended", e)
      useInScope(httpFor(baseUrl, password))(_.close())

    /** Drains stderr so a chatty launcher can't fill the pipe, returning the
      * last lines for a start-failure message.
      */
    private def stderrTail(process: PipedCliProcess): Vector[String] =
      try
        process.stderrLines.foldLeft(Vector.empty[String]): (tail, line) =>
          log.debug("opencode serve stderr: {}", line)
          (tail :+ line).takeRight(MaxErrTailLines)
      catch
        case NonFatal(e) =>
          log.debug("opencode stderr drain ended", e)
          Vector.empty

    // stdout closed with no listening line: the launcher or serve exited.
    // Destroy first so the stderr read EOFs and the join can't hang.
    private def failedToStart(
        process: PipedCliProcess,
        errTail: Fork[Vector[String]]
    ): Nothing =
      process.destroyForciblyTree()
      val tail = errTail.join().mkString("\n")
      throw OrcaFlowException(
        "opencode serve did not start" +
          (if tail.nonEmpty then s":\n$tail"
           else " and produced no error output")
      )
