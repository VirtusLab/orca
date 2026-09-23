package orca.shell.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.runner.manifest.SessionKind
import orca.shell.flows.DiscoveredFlow
import orca.shell.sessions.{RecordedAttempt, SessionPicker}
import orca.shell.ui.Choice

/** The CLI's table/JSON rendering (ADR 0021 §10) — the row shapes `list` and
  * `continue --list` emit, their jsoniter codecs, and the shared space-padded
  * table printer. Shared by [[ListCli]] and [[ContinueCli]].
  */
private[cli] object Tables:

  // --- continue --list ---

  // `lastActiveAt` is the parsed instant rendered back to ISO-8601.
  private[cli] case class SessionRow(
      index: Int,
      /** The bare name `orca continue <name>` matches, or the agent name for an
        * ephemeral session, which was minted under no name.
        */
      sessionName: String,
      /** The attempt's working directory: the listing spans worktrees, so two
        * rows can otherwise be identical — and `continue <name>` then refuses
        * them as ambiguous, naming directories the listing never showed.
        */
      workDir: String,
      /** The attempt's bound branch; `null` in `--json` when none was recorded.
        */
      branch: Option[String],
      kind: SessionKind,
      stage: Option[String],
      /** The path id of the stage that minted the session — the half of its key
        * a row's `stage` (where it was last active) does not carry, and the
        * only thing telling two same-named lineages apart. `None` for an
        * ephemeral session, which was minted under no key.
        */
      sessionStage: Option[String],
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

  private[cli] def sessionListingRows(
      attempts: List[RecordedAttempt]
  ): List[SessionRow] =
    SessionPicker
      .withoutExpanders(SessionPicker.sessionRows(attempts, expanded = true))
      .zipWithIndex
      .collect:
        case (
              choice @ Choice(
                SessionPicker.PickerRow.Resume(selection),
                _,
                _
              ),
              i
            ) =>
          val session = selection.session
          SessionRow(
            index = i + 1,
            sessionName = SessionPicker.displayName(session),
            workDir = selection.manifest.workDir,
            branch = selection.manifest.branch,
            kind = session.kind,
            stage = session.stage,
            sessionStage = session.minted.map(_.stage.value),
            harness = SessionPicker.harnessSettingsName(session.harness),
            lastActiveAt = session.lastActiveAt.toString,
            resumable = choice.isEnabled,
            reason = choice.disabledReason,
            crashed = selection.crashed
          )

  private[cli] def printSessionListing(
      attempts: List[RecordedAttempt],
      asJson: Boolean
  ): Unit =
    val rows = sessionListingRows(attempts)
    if asJson then println(writeToString(rows))
    else if rows.isEmpty then println("(no sessions recorded)")
    else
      // The same decision the interactive picker makes, over the same attempts.
      val tag = SessionPicker.dirTag(attempts)
      val cols = rows.map: r =>
        val status =
          if r.resumable then ""
          else s"  not resumable: ${r.reason.getOrElse("")}"
        val sessionName =
          r.sessionName + (if r.crashed then " (crashed)" else "") +
            tag(r.workDir, r.branch)
        (
          r.index.toString,
          sessionName,
          r.branch.getOrElse(""),
          r.kind.toString,
          r.stage.getOrElse(""),
          // Its own column rather than the picker's conditional marker: a
          // listing is read to tell rows apart, and here the width is free.
          r.sessionStage.getOrElse(""),
          r.harness,
          r.lastActiveAt,
          status
        )
      val header =
        (
          "#",
          "session",
          "branch",
          "kind",
          "stage",
          "minted in",
          "harness",
          "last active",
          ""
        )
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
      flow.origin.label,
      flow.path.toString,
      flow.shadows.map(_.label)
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
          else s"shadows ${f.shadows.map(_.label).mkString(", ")}"
        (
          f.name,
          f.description.getOrElse("(no description)"),
          f.origin.label,
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
