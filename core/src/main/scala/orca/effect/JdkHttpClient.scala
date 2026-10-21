package orca.effect

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

/** The `java.net.http` binding behind [[Network.httpClient]]. */
private[effect] final class JdkHttpClient(
    baseUrl: String,
    user: String,
    password: String
) extends HttpClient:

  // HTTP/1.1: a cleartext h2c upgrade hangs some servers (opencode) on a POST
  // with a body.
  private val client: JHttpClient =
    JHttpClient.newBuilder().version(JHttpClient.Version.HTTP_1_1).build()

  // Preemptive: java.net.http's `Authenticator` only answers a challenge.
  private val authHeader: String =
    "Basic " + Base64.getEncoder.encodeToString(
      s"$user:$password".getBytes(StandardCharsets.UTF_8)
    )

  def get(path: String, timeout: FiniteDuration): HttpReply =
    send(request(path).timeout(timeout.toJava).GET().build())

  def postJson(path: String, json: String, timeout: FiniteDuration): HttpReply =
    send(
      request(path)
        .timeout(timeout.toJava)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build()
    )

  def openLines(path: String): HttpLines =
    // A request timeout would also bound reading the body on newer JDKs.
    val body = client
      .send(
        request(path).GET().build(),
        HttpResponse.BodyHandlers.ofInputStream()
      )
      .body()
    val reader =
      new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))
    new HttpLines:
      def lines: Iterator[String] =
        Iterator.continually(reader.readLine()).takeWhile(_ != null)
      // Closing the body stream unblocks a parked `readLine`; closing an
      // `ofLines` stream does not.
      def close(): Unit = body.close()

  /** Abandons in-flight requests: an open line stream may outlive its reader.
    */
  def shutdown(): Unit = client.shutdownNow()

  private def send(req: HttpRequest): HttpReply =
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    HttpReply(resp.statusCode(), resp.body())

  private def request(path: String): HttpRequest.Builder =
    HttpRequest
      .newBuilder(URI.create(baseUrl + path))
      .header("Authorization", authHeader)
