package orca.tools.opencode

import orca.OrcaFlowException
import orca.backend.StreamSource

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.Base64
import ox.sleep
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** [[OpencodeHttp]] over the JDK `java.net.http` client (ADR 0014). One
  * instance per server; the SSE `events` stream is a lazy line iterator over
  * the response body that `interrupt()` closes.
  */
private[opencode] object JavaNetOpencodeHttp:
  /** Build a client for an already-listening server and block until `GET /doc`
    * answers (the bind line can precede readiness — e.g. a first-run DB
    * migration). Throws if it never becomes healthy.
    */
  def start(baseUrl: String, password: String): OpencodeHttp =
    val http = new JavaNetOpencodeHttp(baseUrl, password)
    http.awaitHealthy(attempts = 50, delayMs = 200L)
    http

private[opencode] class JavaNetOpencodeHttp(baseUrl: String, password: String)
    extends OpencodeHttp:

  // JDK java.net.http, not sttp (whose client isn't a dependency — only
  // sttp-apispec/tapir are). Pinned to HTTP/1.1: the server hangs on the h2c
  // upgrade for a POST-with-body (a GET upgrades fine), wedging the first
  // `POST /session`.
  private val client: HttpClient =
    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  // Preemptive Basic auth set by hand (java.net.http's `Authenticator` is
  // challenge/response only). User is the server default "opencode"; the
  // password reached the server via our env on spawn.
  private val authHeader: String =
    "Basic " + Base64.getEncoder.encodeToString(
      s"opencode:$password".getBytes(StandardCharsets.UTF_8)
    )

  def postJson(path: String, body: String): String =
    val req = request(path)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    if resp.statusCode() / 100 == 2 then resp.body()
    else
      throw OrcaFlowException(
        s"opencode POST $path failed: ${resp.statusCode()} ${resp.body()}"
      )

  override def getStatus(path: String): Int =
    try
      val req = request(path).GET().build()
      client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    catch case NonFatal(_) => 0

  def events(): StreamSource =
    val req = request("/event").GET().build()
    // `ofInputStream` returns once headers arrive; we read lines off the raw
    // body ourselves. Closing the InputStream reliably unblocks a thread parked
    // in `readLine()` on the open-ended SSE stream — `ofLines().close()` does
    // not, which deadlocks the reader at turn end.
    val body =
      client.send(req, HttpResponse.BodyHandlers.ofInputStream()).body()
    val reader =
      new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))
    new StreamSource:
      def lines: Iterator[String] =
        Iterator.continually(reader.readLine()).takeWhile(_ != null)
      def errorLines: Iterator[String] = Iterator.empty
      def interrupt(): Unit = body.close()
      def tryExitCode: Option[Int] = Some(0)

  /** Forceful shutdown — does not wait for in-flight requests (an SSE stream
    * may still be open at teardown), so `shutdownNow` over the blocking
    * `close`.
    */
  override def close(): Unit = client.shutdownNow()

  /** Poll `GET /doc` until it answers 200 or attempts run out, sleeping only
    * between attempts.
    */
  private def awaitHealthy(attempts: Int, delayMs: Long): Unit =
    val healthy = Iterator
      .range(0, attempts)
      .exists: attempt =>
        if attempt > 0 then sleep(delayMs.millis)
        pingOk()
    if !healthy then
      throw OrcaFlowException(
        s"opencode server at $baseUrl never became healthy"
      )

  private def pingOk(): Boolean =
    try
      val req = request("/doc").GET().build()
      client
        .send(req, HttpResponse.BodyHandlers.discarding())
        .statusCode() == 200
    catch case NonFatal(_) => false

  private def request(path: String): HttpRequest.Builder =
    HttpRequest
      .newBuilder(URI.create(baseUrl + path))
      .header("Authorization", authHeader)
