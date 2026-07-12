package orca.backend.mcp

import ox.Ox
import ox.channels.BufferCapacity

import scala.util.control.NonFatal

/** One-conversation ask-user wiring: the host-side [[AskUserBridge]] (which
  * surfaces `UserQuestion` events to the renderer), the Netty-backed
  * [[AskUserMcpServer]], and any backend-specific `extras` (e.g. claude's
  * workDir-local `.orca-mcp-<port>.json` config file deletion). Lifetime is
  * tied to a single conversation; the framework closes it after the read loop
  * drains.
  *
  * Encapsulates close-order: bridge first (errors any in-flight `ask` so
  * blocked handlers exit before the binding tears down), then the server, then
  * extras. Each close is wrapped — one resource's failure mustn't skip the
  * next, and close-time failure mustn't mask the caller's original throw.
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

  /** Stand up the bridge + Netty MCP server, then invoke the backend- specific
    * `extras` callback to allocate any additional cleanup-needing artefacts
    * (claude writes a workDir-local config file; codex doesn't need any). If
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
        // No drainer thread is running yet so the bridge has no blocked
        // callers to error, but close it for symmetry with the normal
        // tear-down path — if anything moves drainer-start earlier later,
        // the cleanup stays right.
        swallow(bridge.close())
        swallow(server.close())
        throw e

  private def swallow(action: => Unit): Unit =
    try action
    catch case NonFatal(_) => ()
