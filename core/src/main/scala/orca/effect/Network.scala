package orca.effect

import language.experimental.captureChecking

import caps.assumeSafe
import ox.{Ox, ResourceScope}
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

import scala.concurrent.duration.FiniteDuration

/** Authority to open network connections and bind local servers. */
@assumeSafe
final class Network private () extends caps.SharedCapability:

  /** Serves `endpoints` on `127.0.0.1` at an ephemeral port until the enclosing
    * scope ends, and returns the port. Requests are handled in forks of that
    * scope. A failure to stop the server is logged, not thrown.
    */
  def serveLocal(
      endpoints: List[ServerEndpoint[Any, Identity]],
      requestTimeout: FiniteDuration
  )(using Ox): Int =
    LocalServer.serve(endpoints, requestTimeout)

  /** An HTTP/1.1 client for the server at `baseUrl`, sending Basic auth
    * preemptively on every request. Shut down when the enclosing scope ends,
    * abandoning in-flight requests and open line streams.
    */
  def httpClient(baseUrl: String, user: String, password: String)(using
      ResourceScope
  ): HttpClient =
    ox.useInScope(JdkHttpClient(baseUrl, user, password))(_.shutdown())

object Network:
  /** For wiring and tests only: safe code cannot call this. */
  private[orca] def ambient: Network = new Network()
