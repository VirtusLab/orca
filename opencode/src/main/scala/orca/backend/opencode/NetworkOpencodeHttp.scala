package orca.backend.opencode

import orca.OrcaFlowException
import orca.backend.StreamSource
import orca.effect.{HttpClient, Network}
import orca.concurrency.sleep
import orca.safe.prelude.{DurationInt, NonFatal}
import ox.ResourceScope

import scala.concurrent.duration.FiniteDuration

/** [[OpencodeHttp]] over [[Network.httpClient]] (ADR 0014). */
private[opencode] object NetworkOpencodeHttp:

  /** Bounds a request/response call, so a hung server fails it rather than
    * blocking the caller (e.g. a turn cancel's abort POST).
    */
  private val RequestTimeout: FiniteDuration = 30.seconds

  /** A health ping is retried, so a hung one should give way to the next. */
  private val PingTimeout: FiniteDuration = 1.second

  /** A client for an already-listening server, once `GET /doc` answers (the
    * bind line can precede readiness, e.g. a first-run DB migration). Throws if
    * it never does. The client lives until the enclosing scope ends.
    */
  def start(net: Network, baseUrl: String, password: String)(using
      ResourceScope
  ): OpencodeHttp =
    // User is the server default; the password reached it via the spawn env.
    connect(net.httpClient(baseUrl, "opencode", password), baseUrl)

  /** [[start]] over an already-built `client`. */
  private[opencode] def connect(
      client: HttpClient,
      baseUrl: String
  ): OpencodeHttp =
    awaitHealthy(client, baseUrl, attempts = 50, delay = 200.millis)
    new NetworkOpencodeHttp(client)

  private def awaitHealthy(
      client: HttpClient,
      baseUrl: String,
      attempts: Int,
      delay: FiniteDuration
  ): Unit =
    val healthy = (0 until attempts).exists: attempt =>
      if attempt > 0 then sleep(delay)
      pingOk(client)
    if !healthy then
      throw OrcaFlowException(
        s"opencode server at $baseUrl never became healthy"
      )

  private def pingOk(client: HttpClient): Boolean =
    try client.get("/doc", PingTimeout).status == 200
    catch case NonFatal(_) => false

private[opencode] final class NetworkOpencodeHttp(client: HttpClient)
    extends OpencodeHttp:
  import NetworkOpencodeHttp.RequestTimeout

  def postJson(path: String, body: String): String =
    val reply = client.postJson(path, body, RequestTimeout)
    if reply.isSuccess then reply.body
    else
      throw OrcaFlowException(
        s"opencode POST $path failed: ${reply.status} ${reply.body}"
      )

  override def getStatus(path: String): Int =
    try client.get(path, RequestTimeout).status
    catch case NonFatal(_) => 0

  def events(): StreamSource =
    val stream = client.openLines("/event")
    new StreamSource:
      def lines: Iterator[String] = stream.lines
      def errorLines: Iterator[String] = Nil.iterator
      def interrupt(): Unit = stream.close()
      def tryExitCode: Option[Int] = Some(0)
