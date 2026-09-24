package orca.backend.mcp

import ox.Ox
import ox.channels.BufferCapacity

/** One turn's ask-user wiring: the host-side [[AskUserBridge]] and the
  * [[AskUserMcpServer]] serving it. Both live as long as the enclosing turn
  * scope — the server is registered with it, and its handlers blocked on the
  * bridge are forks of it.
  */
private[orca] case class AskUserSession(bridge: AskUserBridge, server: McpHost)

private[orca] object AskUserSession:

  def allocate()(using Ox, BufferCapacity): AskUserSession =
    val bridge = new AskUserBridge
    AskUserSession(bridge, AskUserMcpServer.start(bridge))
