package orca.sweep

import java.util.UUID

/** A unique marker orca puts in the environment of every agent process it
  * spawns, so work that process leaves running can still be traced back to the
  * turn that started it.
  *
  * The environment is what makes this reach where parent links cannot: `fork`
  * and `exec` copy it unconditionally, so the cookie survives `setsid`, a
  * double fork and reparenting to init — exactly the moves that take a process
  * out of `ProcessHandle.descendants`. [[EnvCookieSweep]] finds what still
  * carries it.
  *
  * One cookie per spawn, minted in [[orca.subprocess.CliRunner.spawnPiped]].
  * Every agent turn spawns a fresh CLI process, so a survivor names the turn
  * that started it.
  */
opaque type EnvCookie = String

object EnvCookie:

  /** Environment variable the cookie travels in. */
  val VarName: String = "ORCA_TURN_COOKIE"

  def mint(): EnvCookie = UUID.randomUUID().toString

  extension (cookie: EnvCookie)
    def value: String = cookie

    /** The `NAME=value` pair as it appears in `/proc/<pid>/environ`. */
    private[sweep] def environEntry: String = s"$VarName=$cookie"
