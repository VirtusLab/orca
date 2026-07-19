package orca.shell.sessions

import orca.agents.BackendTag
import orca.runner.ManifestSession
import orca.settings.AgentSpec
import orca.shell.ui.Choice

import java.time.Instant
import scala.util.Try

/** The continue-a-session picker (ADR 0021 §8): groups, sorts, and labels the
  * sessions across every recorded run into selectable rows, and resolves a
  * CLI-style selector (index / name / newest) to a [[SessionSelection]]. Shared
  * by the interactive menu (`Main.continueSession`) and the CLI's `continue`
  * command.
  */
private[shell] object SessionPicker:

  /** One session occurrence paired with the run it came from — the unit
    * [[sessionRows]] groups, sorts, and labels. Carrying the whole [[RecordedRun]]
    * (not just its `crashed` flag) keeps [[SessionSelection]] constructible
    * straight from an occurrence.
    */
  private case class Occurrence(run: RecordedRun, session: ManifestSession)

  /** One outcome of the continue-session picker: either resume a specific
    * session, or re-render the picker with the collapsed groups (older lineage
    * occurrences, one-shots) expanded.
    */
  private[shell] enum PickerRow:
    case Resume(selection: SessionSelection)
    case ShowMore

  /** Builds the continue-session picker's rows (ADR 0021 §8, research 08 items
    * 7+8): durable lineages first, one-shots last, with two kinds of rows
    * collapsed by default behind an expander.
    *
    * A durable lineage is a `(agent, sessionName)` pair with `kind ==
    * "durable"` — every occurrence of it across every run in `runs`, not just
    * the newest run, since a lineage's `sessionName` is stable across separate
    * flow runs (a fresh run mints a fresh `clientId`/`wireId` but reuses the
    * same `agent.session(name, ...)` name) while a single run's own durable
    * session always upserts onto one manifest row. Only the occurrence with the
    * max `lastActiveAt` is shown (marked `★ ... — latest`, the primary
    * continuation target); the rest collapse behind a "show N earlier
    * occurrences" row. One-shot sessions (`kind == "oneShot"` — Plan-stage
    * calls, reviewer-selection calls, reviewer `chat()` runs) are never deduped
    * — each is a genuinely distinct fresh session — but collapse behind a
    * single "show N one-shot sessions" row, since these are the rows that
    * otherwise flood the picker with same-named, low-value entries.
    *
    * `expanded` reveals both collapsed groups in place, sorted the same as the
    * primary rows (newest `lastActiveAt` first). Disabling a row previews only
    * what [[ResumeCommand.staticGate]] can tell without a live harness call: a
    * wireId-less session (pi always) or an unrecognised harness. Gemini's real
    * resumability needs `gemini --list-sessions` (deferred to selection, in
    * [[orca.shell.actions.SessionAction.resume]]) — `staticGate` passes any
    * gemini session with a wireId, leaving its row enabled pending that later
    * check.
    */
  private[shell] def sessionRows(
      runs: List[RecordedRun],
      expanded: Boolean
  ): List[Choice[PickerRow]] =
    val occurrences =
      for
        run <- runs
        session <- run.manifest.sessions
      yield Occurrence(run, session)
    val (durable, oneShot) = occurrences.partition(_.session.kind == "durable")

    val lineages = durable
      .groupBy(o => (o.session.agent, o.session.sessionName))
      .values
      .map(_.sortBy(recency).reverse)
      .toList
    val primary = lineages.map(_.head).sortBy(recency).reverse
    val earlier = lineages.flatMap(_.tail).sortBy(recency).reverse
    val oneShotSorted = oneShot.sortBy(recency).reverse

    val primaryRows = primary.map(o => resumeRow(o, primaryLabel(o)))
    val earlierRows =
      if expanded then earlier.map(o => resumeRow(o, earlierLabel(o)))
      else expanderRow(earlier.size, "earlier occurrence")
    val oneShotRows =
      if expanded then oneShotSorted.map(o => resumeRow(o, oneShotLabel(o)))
      else
        expanderRow(
          oneShotSorted.size,
          "one-shot session",
          " (reviews, plan steps)"
        )

    primaryRows ++ earlierRows ++ oneShotRows

  /** Parses `session.lastActiveAt` — always a valid `Instant` from the writer
    * (`clock().toString`), but falls back to the epoch rather than throwing on
    * a hand-edited or future-schema manifest, so one bad row can't take down
    * the whole picker.
    */
  private def recency(o: Occurrence): Instant =
    Try(Instant.parse(o.session.lastActiveAt)).getOrElse(Instant.EPOCH)

  private def resumeRow(o: Occurrence, label: String): Choice[PickerRow] =
    Choice(
      PickerRow.Resume(
        SessionSelection(o.run.manifest, o.session, o.run.crashed)
      ),
      label,
      disabledReason = ResumeCommand.staticGate(o.session).left.toOption
    )

  /** A single "show N ..." expander row, or `Nil` when there's nothing to
    * reveal — omitting the row entirely rather than showing "show 0 ...".
    */
  private def expanderRow(
      count: Int,
      noun: String,
      suffix: String = ""
  ): List[Choice[PickerRow]] =
    if count == 0 then Nil
    else
      val plural = if count == 1 then "" else "s"
      List(Choice(PickerRow.ShowMore, s"… show $count $noun$plural$suffix"))

  /** `★ <sessionName> — latest (stage: <stage>) [<harness>]`, or `(no stage
    * yet)` when the durable session hasn't entered a stage (rare — custom flows
    * only, per research 08 item 7+8 §5). Falls back to the agent name if a
    * malformed manifest somehow has `kind == "durable"` without a
    * `sessionName`.
    */
  private def primaryLabel(o: Occurrence): String =
    val name = o.session.sessionName.getOrElse(o.session.agent)
    val stage = o.session.stage.fold("no stage yet")(s => s"stage: $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.run.crashed then " (crashed)" else ""
    s"★ $name — latest ($stage) [$harness]$crashedSuffix"

  /** `<sessionName> — stage <stage> [<harness>] (earlier occurrence)`, shown
    * only when the picker is expanded.
    */
  private def earlierLabel(o: Occurrence): String =
    val name = o.session.sessionName.getOrElse(o.session.agent)
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.run.crashed then " (crashed)" else ""
    s"$name$stage [$harness] (earlier occurrence)$crashedSuffix"

  /** `<agent> (<role>) — stage <stage> [<harness>] (one-shot)`, omitting the
    * role/stage segments when absent; shown only when the picker is expanded.
    */
  private def oneShotLabel(o: Occurrence): String =
    val role = o.session.role.fold("")(r => s" ($r)")
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.run.crashed then " (crashed)" else ""
    s"${o.session.agent}$role$stage [$harness] (one-shot)$crashedSuffix"

  /** The settings-file harness name (`claude`, `codex`, …) for a manifest's
    * [[BackendTag.wireName]] string, falling back to the raw string for an
    * unrecognised one (the row itself is disabled in that case, so this is
    * display-only).
    */
  private[shell] def harnessSettingsName(wireName: String): String =
    BackendTag
      .fromWireName(wireName)
      .flatMap(AgentSpec.harnessNameFor.get)
      .getOrElse(wireName)

  /** Resolves a `continue` selector to a session: no selector picks the newest
    * durable lineage, a numeric selector picks that 1-based row from the full
    * (expanded) listing, and anything else is matched by session name.
    */
  private[shell] def resolveSelection(
      runs: List[RecordedRun],
      selector: Option[String]
  ): Either[String, SessionSelection] =
    selector match
      case None => newestDurableSelection(runs)
      case Some(s) =>
        s.toIntOption match
          case Some(index) => selectByIndex(runs, index)
          case None        => selectByName(runs, s)

  private[shell] def newestDurableSelection(
      runs: List[RecordedRun]
  ): Either[String, SessionSelection] =
    sessionRows(runs, expanded = false).headOption match
      case None => Left("no sessions recorded yet")
      case Some(choice) =>
        choice.value match
          case PickerRow.Resume(selection) =>
            choice.disabledReason match
              case Some(reason) =>
                Left(s"can't resume the newest session — $reason")
              case None => Right(selection)
          case PickerRow.ShowMore =>
            Left(
              "no durable session to continue yet — see `orca continue --list`"
            )

  private[shell] def selectByIndex(
      runs: List[RecordedRun],
      index: Int
  ): Either[String, SessionSelection] =
    val rows = withoutExpanders(sessionRows(runs, expanded = true))
    rows.lift(index - 1) match
      case None =>
        Left(
          s"no session at index $index — see `orca continue --list` (1-${rows.size})"
        )
      case Some(choice) =>
        choice.value match
          case PickerRow.Resume(selection) =>
            choice.disabledReason match
              case Some(reason) =>
                Left(s"session $index isn't resumable — $reason")
              case None => Right(selection)
          case PickerRow.ShowMore =>
            // unreachable: withoutExpanders already dropped every ShowMore row
            Left(s"no session at index $index")

  private[shell] def selectByName(
      runs: List[RecordedRun],
      name: String
  ): Either[String, SessionSelection] =
    val matches =
      withoutExpanders(sessionRows(runs, expanded = false)).collect:
        case choice @ Choice(PickerRow.Resume(selection), _, _, _)
            if selection.session.sessionName.contains(name) =>
          (selection, choice.disabledReason)
    matches match
      case Nil =>
        Left(s"no session named '$name' found — see `orca continue --list`")
      case (selection, None) :: Nil => Right(selection)
      case (_, Some(reason)) :: Nil =>
        Left(s"session '$name' isn't resumable — $reason")
      case multiple =>
        val agents = multiple.map(_._1.session.agent).distinct.mkString(", ")
        Left(s"'$name' is ambiguous — matches agents: $agents")

  /** [[sessionRows]]'s rows, dropping the "show more" expanders — never present
    * for [[SessionSelection]] callers (`selectByIndex` reads the fully expanded
    * listing, `selectByName`/`newestDurableSelection` only ever resolve to an
    * actual session or fail).
    */
  private[shell] def withoutExpanders(
      rows: List[Choice[PickerRow]]
  ): List[Choice[PickerRow]] =
    rows.filter(_.value != PickerRow.ShowMore)
