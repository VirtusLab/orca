package orca.shell.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.shell.flows.DiscoveredFlow
import orca.shell.sessions.{RecordedRun, SessionPicker}
import orca.shell.ui.Choice

/** The CLI's table/JSON rendering (ADR 0021 §10) — the row shapes `list` and
  * `continue --list` emit, their jsoniter codecs, and the shared space-padded
  * table printer. Shared by [[ListCli]] and [[ContinueCli]].
  */
private[cli] object Tables:

  // --- continue --list ---

  private[cli] case class SessionRow(
      index: Int,
      sessionName: String,
      kind: String,
      stage: Option[String],
      harness: String,
      lastActiveAt: String,
      resumable: Boolean,
      reason: Option[String],
      crashed: Boolean
  )
  // `withTransientEmpty`/`withTransientNone` false: `--json` output is for
  // scripts, which should see an always-present `reason` key (null when
  // unset) and `shadows`/similar fields rather than a silently vanishing one.
  private given sessionRowsCodec: JsonValueCodec[List[SessionRow]] =
    ConfiguredJsonValueCodec.derived[List[SessionRow]](using
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(false)
    )

  private[cli] def sessionListingRows(runs: List[RecordedRun]): List[SessionRow] =
    SessionPicker
      .withoutExpanders(SessionPicker.sessionRows(runs, expanded = true))
      .zipWithIndex
      .collect:
        case (
              choice @ Choice(
                SessionPicker.PickerRow.Resume(selection),
                _,
                _,
                _
              ),
              i
            ) =>
          val session = selection.session
          SessionRow(
            index = i + 1,
            sessionName = session.sessionName.getOrElse(session.agent),
            kind = session.kind,
            stage = session.stage,
            harness = SessionPicker.harnessSettingsName(session.harness),
            lastActiveAt = session.lastActiveAt,
            resumable = choice.isEnabled,
            reason = choice.disabledReason,
            crashed = selection.crashed
          )

  private[cli] def printSessionListing(
      runs: List[RecordedRun],
      asJson: Boolean
  ): Unit =
    val rows = sessionListingRows(runs)
    if asJson then println(writeToString(rows))
    else if rows.isEmpty then println("(no sessions recorded)")
    else
      val cols = rows.map: r =>
        val status =
          if r.resumable then ""
          else s"  not resumable: ${r.reason.getOrElse("")}"
        val sessionName =
          r.sessionName + (if r.crashed then " (crashed)" else "")
        (
          r.index.toString,
          sessionName,
          r.kind,
          r.stage.getOrElse(""),
          r.harness,
          r.lastActiveAt,
          status
        )
      val header =
        ("#", "session", "kind", "stage", "harness", "last active", "")
      printTable(header +: cols)

  // --- list ---

  private[cli] case class FlowRow(
      name: String,
      description: Option[String],
      origin: String,
      path: String,
      shadows: List[String]
  )
  private given flowRowsCodec: JsonValueCodec[List[FlowRow]] =
    ConfiguredJsonValueCodec.derived[List[FlowRow]](using
      CodecMakerConfig.withTransientEmpty(false).withTransientNone(false)
    )

  private[cli] def toFlowRow(flow: DiscoveredFlow): FlowRow =
    FlowRow(
      flow.name,
      flow.description,
      flow.origin.originLabel,
      flow.path.toString,
      flow.shadows.map(_.originLabel)
    )

  private[cli] def printFlows(
      flows: List[DiscoveredFlow],
      asJson: Boolean
  ): Unit =
    if asJson then println(writeToString(flows.map(toFlowRow)))
    else printFlowTable(flows)

  private def printFlowTable(flows: List[DiscoveredFlow]): Unit =
    if flows.isEmpty then println("(no flows found)")
    else
      val cols = flows.map: f =>
        val shadows =
          if f.shadows.isEmpty then ""
          else s"shadows ${f.shadows.map(_.originLabel).mkString(", ")}"
        (
          f.name,
          f.description.getOrElse("(no description)"),
          f.origin.originLabel,
          shadows
        )
      val header = ("name", "description", "origin", "")
      printTable(header +: cols)

  /** Space-padded columns, header row included — the shared rendering
    * [[printFlowTable]] and [[printSessionListing]] both use. Trailing empty
    * cells in a row (an unshadowed flow, a resumable session) print no
    * padding-driven trailing whitespace.
    */
  private def printTable(rows: Seq[Product]): Unit =
    val asRows = rows.map(_.productIterator.map(_.toString).toIndexedSeq)
    val widths = asRows.transpose.map(_.map(_.length).max)
    asRows.foreach: cells =>
      val line = cells
        .zip(widths)
        .map((cell, width) => cell.padTo(width, ' '))
        .mkString("  ")
        .stripTrailing()
      println(line)
