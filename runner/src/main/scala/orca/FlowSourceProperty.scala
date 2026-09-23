package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.agents.JsonData
import orca.progress.FlowSource

import scala.util.Try

/** How the shell tells the flow child its [[FlowSource]]: a JVM system
  * property, set with `scala-cli --java-prop`. Per-JVM, so the agents and other
  * processes a flow starts don't inherit it.
  */
private[orca] object FlowSourceProperty:

  val Name: String = "orca.flow"

  /** The `--java-prop` value: `orca.flow=<source>`. */
  def assignment(source: FlowSource): String =
    s"$Name=${writeToString(source)(using codec)}"

  /** The [[FlowSource]] this JVM was launched with, `None` for a flow run any
    * other way — and for an undecodable value, which is warned about.
    */
  def read(): Option[FlowSource] =
    sys.props
      .get(Name)
      .flatMap: raw =>
        decode(raw) match
          case Right(source) => Some(source)
          case Left(error) =>
            System.err.println(
              s"[orca] ignoring the $Name property ($error); the orca shell " +
                "won't offer to resume this run"
            )
            None

  /** `Left` with the decode error when `raw` isn't an [[assignment]] value. */
  private[orca] def decode(raw: String): Either[String, FlowSource] =
    Try(readFromString(raw)(using codec)).toEither.left.map(_.getMessage)

  private def codec = summon[JsonData[FlowSource]].codec
