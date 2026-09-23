package orca.backend

import orca.OrcaFlowException
import orca.events.OrcaListener
import orca.subprocess.PipedCliProcess
import orca.sweep.EnvCookieSweep

import ox.{ResourceScope, releaseAfterScope}

/** Spawns one turn's agent process and wraps it in a [[Conversation]], for the
  * subprocess backends (claude/codex/gemini/pi).
  *
  *   - `spawn` builds the argv and launches the process.
  *   - `build` wraps the live process. If it throws, the process tree is killed
  *     and the failure is rethrown as "Failed to open <sessionLabel> session".
  *
  * Once spawned, the process's environment cookie is swept when the turn scope
  * ends ([[EnvCookieSweep.afterTurn]], reporting to `events`) — after the
  * turn's teardown killed the process tree.
  *
  * `sessionLabel` is the backend's descriptor for the failure message —
  * deliberately not the bare backend name, which is pinned by tests.
  */
private[orca] object SubprocessSpawn:

  def open[C](sessionLabel: String, events: OrcaListener)(
      spawn: => PipedCliProcess
  )(build: PipedCliProcess => C)(using ResourceScope): C =
    val process = spawn
    releaseAfterScope(EnvCookieSweep.afterTurn(process.envCookie, events))
    try build(process)
    catch
      case e: Exception =>
        // No conversation exists to tear the process down.
        process.sendSigInt()
        process.destroyForciblyTree()
        throw OrcaFlowException(
          s"Failed to open $sessionLabel session: ${e.getMessage}"
        )
