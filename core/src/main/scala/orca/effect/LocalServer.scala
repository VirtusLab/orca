package orca.effect

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import org.slf4j.LoggerFactory
import ox.{Ox, useInScope}
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.NettyConfig.EventLoopConfig
import sttp.tapir.server.netty.sync.NettySyncServer

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** The Netty binding behind [[Network.serveLocal]]. */
private[effect] object LocalServer:

  private val log = LoggerFactory.getLogger("orca.effect")

  def serve(
      endpoints: List[ServerEndpoint[Any, Identity]],
      requestTimeout: FiniteDuration
  )(using Ox): Int =
    // tapir's own group waits out a 2s shutdown quiet period, twice per stop.
    val group = useInScope(
      MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    )(shutdown)
    // tapir's `start()` leaves teardown to the caller, and a leaked binding
    // strands its event-loop threads for the life of the JVM.
    val binding = useInScope(
      NettySyncServer()
        .host("127.0.0.1")
        .port(0)
        .modifyConfig(
          // Netty requires the idle timeout to exceed the request timeout.
          _.requestTimeout(requestTimeout)
            .idleTimeout(requestTimeout + 1.minute)
            .eventLoopConfig(
              EventLoopConfig(() => group, classOf[NioServerSocketChannel])
            )
            .withDontShutdownEventLoopGroupOnClose
        )
        .addEndpoints(endpoints)
        .start()
    ): b =>
      try b.stop()
      catch case NonFatal(e) => log.warn("local server stop failed", e)
    binding.port

  // Uninterruptible: the wait is bounded by the 2s timeout, and an interrupted
  // scope teardown would otherwise leave the event-loop thread running.
  private def shutdown(group: MultiThreadIoEventLoopGroup): Unit =
    try
      group
        .shutdownGracefully(0, 2, TimeUnit.SECONDS)
        .syncUninterruptibly(): Unit
    catch case NonFatal(e) => log.warn("local server shutdown failed", e)
