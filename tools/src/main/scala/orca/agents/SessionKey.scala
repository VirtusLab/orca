package orca.agents

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import orca.StagePath

/** What identifies a durable session: the `name` it was minted under (its role,
  * e.g. `implementer`) and the stage that minted it. Two stages can therefore
  * never name the same session, and one stage cannot mint the same name twice.
  *
  * Built only inside orca, never by a flow script:
  * `FlowControl.claimSessionKey` mints one for a session about to be created —
  * the only door that also claims it — while `SessionRecord.key` rebuilds one
  * from persisted halves and `ManifestSession.minted` reads one back through
  * [[SessionKey.codec]].
  *
  * Minted by `agent.session(name, seed)`, persisted as an
  * `orca.sessions.SessionRecord`, and carried onto
  * [[orca.events.OrcaEvent.SessionCommitted]] so the attempt manifest and the
  * shell's session picker can tell same-named sessions apart.
  */
case class SessionKey private[orca] (name: String, stage: StagePath):
  /** How the key reads in a run's own diagnostics: the name, and the stage that
    * owns it. Diagnostic, not a label — a stage path id carries `#0` occurrence
    * suffixes, so the shell's picker and `orca continue --list` show the bare
    * name and print the minting stage only where two rows would otherwise be
    * indistinguishable.
    */
  def describe: String = stage match
    case StagePath.FlowBody  => s"'$name'"
    case StagePath.Stage(id) => s"'$name' in stage '${id.value}'"

object SessionKey:
  /** `{"name": ..., "stage": ...}`, the stage as its [[StagePath.value]]
    * spelling, for the attempt manifest.
    */
  private[orca] given codec: JsonValueCodec[SessionKey] =
    JsonCodecMaker.make[SessionKey]
