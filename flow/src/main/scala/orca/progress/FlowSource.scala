package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.agents.JsonData

import scala.util.Try

/** The flow script a run executes, as the shell launched it. Recorded in the
  * progress header, so the shell's "Resume interrupted run" relaunches the same
  * script.
  */
enum FlowSource derives JsonData:
  /** Launched by catalog name (`implement.sc`): a resume looks the name up in
    * the catalog again, as re-running `orca run implement` would.
    */
  case Catalog(name: String)

  /** Launched by an absolute path to the script: a resume runs that file.
    * Unchecked here, since a header that fails to decode starts a fresh run
    * over the log; the shell checks the path before offering a resume.
    */
  case File(path: String)

  /** What a user sees: the catalog name, or the full path. */
  def display: String = this match
    case Catalog(name) => name
    case File(path)    => path

  /** The script's filename, e.g. `implement.sc`. */
  def fileName: String = this match
    case Catalog(name) => name
    case File(path)    => path.substring(path.lastIndexOf('/') + 1)

object FlowSource:

  /** The JVM system property through which the shell tells the flow child its
    * [[FlowSource]]. Per-JVM, so the agents and other processes a flow starts
    * don't inherit it.
    */
  val Property: String = "orca.flow"

  def toProperty(source: FlowSource): String =
    writeToString(source)(using summon[JsonData[FlowSource]].codec)

  /** `Left` with the decode error when `raw` isn't a [[toProperty]] value. */
  def fromProperty(raw: String): Either[String, FlowSource] =
    Try(
      readFromString(raw)(using summon[JsonData[FlowSource]].codec)
    ).toEither.left
      .map(_.getMessage)
