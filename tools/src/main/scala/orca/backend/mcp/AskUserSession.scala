package orca.backend.mcp

import ox.Ox
import ox.channels.BufferCapacity

import scala.util.control.NonFatal

/** One-conversation ask-user wiring: the host-side [[AskUserBridge]], the
  * Netty-backed [[AskUserMcpServer]], and any backend-specific `extras` (e.g.
  * claude's `.orca-mcp-<port>.json` config file deletion). The framework closes
  * it after the read loop drains.
  *
  * Close-order: bridge first (errors any in-flight `ask` so blocked handlers
  * exit before the binding tears down), then server, then extras. Each close is
  * wrapped so one resource's failure doesn't skip the next or mask the caller's
  * original throw.
  */
private[orca] case class AskUserSession(
    bridge: AskUserBridge,
    server: AskUserMcpServer,
    extras: List[AutoCloseable]
) extends AutoCloseable:
  import AskUserSession.swallow

  def close(): Unit =
    swallow(bridge.close())
    swallow(server.close())
    extras.foreach(r => swallow(r.close()))

private[orca] object AskUserSession:

  /** Stand up the bridge + Netty MCP server, then invoke the backend-specific
    * `extras` callback to allocate any additional cleanup-needing artefacts. If
    * the callback throws, the bridge + server are closed before the throw
    * escapes so no Netty binding leaks.
    */
  def allocate(
      extras: AskUserMcpServer => List[AutoCloseable] = _ => Nil
  )(using Ox, BufferCapacity): AskUserSession =
    val bridge = new AskUserBridge
    val server = AskUserMcpServer.start(bridge)
    try AskUserSession(bridge, server, extras(server))
    catch
      case NonFatal(e) =>
        // No drainer thread is running yet, but close the bridge too for
        // symmetry with the normal tear-down path.
        swallow(bridge.close())
        swallow(server.close())
        throw e

  private def swallow(action: => Unit): Unit =
    try action
    catch case NonFatal(_) => ()
