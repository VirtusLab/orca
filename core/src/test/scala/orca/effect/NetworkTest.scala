package orca.effect

import ox.supervised
import sttp.tapir.*

import java.net.{ConnectException, InetSocketAddress, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using

class NetworkTest extends munit.FunSuite:

  private val ping =
    endpoint.get.in("ping").out(stringBody).handleSuccess(_ => "pong")

  // Closed before the scope ends: an idle keep-alive connection makes the
  // server's stop wait out its graceful-shutdown timeout.
  private def get(port: Int): String =
    val request =
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/ping")).build()
    Using.resource(HttpClient.newHttpClient()):
      _.send(request, HttpResponse.BodyHandlers.ofString()).body()

  /** Thread-name prefixes of the server's event loop and tapir's executor. */
  private val nettyThreadPrefixes =
    List("multiThreadIoEventLoopGroup", "defaultEventExecutor")

  test("serveLocal serves the endpoints on a loopback port"):
    supervised:
      val port = Network.ambient.serveLocal(List(ping), 5.seconds)
      assertEquals(get(port), "pong")

  test("serveLocal stops serving when the scope ends"):
    val port = supervised(Network.ambient.serveLocal(List(ping), 5.seconds))
    Using.resource(new Socket()): socket =>
      intercept[ConnectException](
        socket.connect(new InetSocketAddress("127.0.0.1", port), 1000)
      ): Unit

  test("serveLocal leaves no Netty threads running when the scope ends"):
    supervised:
      get(Network.ambient.serveLocal(List(ping), 5.seconds)): Unit
    val netty = Thread.getAllStackTraces.keySet.asScala.toList
      .filter(t => nettyThreadPrefixes.exists(t.getName.startsWith))
    // A terminated executor's thread may still be finishing its exit.
    netty.foreach(_.join(1000))
    assertEquals(netty.filter(_.isAlive).map(_.getName), Nil)
