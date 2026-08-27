package orca.shell.sessions

import orca.agents.BackendTag
import orca.runner.manifest.{ManifestSession, ManifestSessionKind}
import orca.settings.AgentSpec
import orca.shell.ui.Choice

import java.time.Instant

/** The continue-a-session picker (ADR 0021 §8): groups, sorts, and labels the
  * sessions across every recorded run into selectable rows, and resolves a
  * CLI-style selector (index / name / newest) to a [[SessionSelection]]. Shared
  * by the interactive menu (`Main.continueSession`) and the CLI's `continue`
  * command.
  */
private[shell] object SessionPicker:

  /** One session occurrence paired with the run it came from — the unit
    * [[sessionRows]] groups, sorts, and labels. Carrying the whole
    * [[RecordedRun]] (not just its `crashed` flag) keeps [[SessionSelection]]
    * constructible straight from an occurrence.
    */
  private case class Occurrence(run: RecordedRun, session: ManifestSession)

  /** One outcome of the continue-session picker: either resume a specific
    * session, or re-render the picker with the collapsed groups (older lineage
    * occurrences, one-shots) expanded.
    */
  private[shell] enum PickerRow:
    case Resume(selection: SessionSelection)
    case ShowMore

  /** Builds the continue-session picker's rows (ADR 0021 §8): durable lineages
    * first, one-shots last, with two kinds of rows collapsed by default behind
    * an expander.
    *
    * A durable lineage is a `(agent, sessionName)` pair with `kind ==
    * [[ManifestSessionKind.Durable]]` — every occurrence of it across every run
    * in `runs`, not just the newest run, since a lineage's `sessionName` is
    * stable across separate flow runs (a fresh run mints a fresh
    * `clientId`/`wireId` but reuses the same `agent.session(name, ...)` name)
    * while a single run's own durable session always upserts onto one manifest
    * row. Only the occurrence with the max `lastActiveAt` is shown (marked `★
    * ... — latest`, the primary continuation target); the rest collapse behind
    * a "show N earlier occurrences" row. One-shot sessions
    * ([[ManifestSessionKind.OneShot]] — Plan-stage calls, reviewer-selection
    * calls, reviewer `chat()` runs) are never deduped — each is a genuinely
    * distinct fresh session — but collapse behind a single "show N one-shot
    * sessions" row, since these are the rows that otherwise flood the picker
    * with same-named, low-value entries.
    *
    * `expanded` reveals both collapsed groups in place, sorted the same as the
    * primary rows (newest `lastActiveAt` first). Disabling a row previews only
    * what [[ResumeCommand.staticGate]] can tell without a live harness call: an
    * unrecognised harness, or a wireId-less session. The checks that need the
    * manifest's `workDir` or a live call — gemini's `gemini --list-sessions`
    * index, pi's session dir — are deferred to selection, in
    * [[orca.shell.actions.SessionAction.resume]], so those rows stay enabled
    * pending that later check.
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
    val (durable, oneShot) = occurrences.partition(isDurable)

    // Keyed on the working directory too: harness sessions are cwd-scoped, and
    // flow session names are static ("implementer" in every run), so the same
    // name in two worktrees is two different conversations about two different
    // tasks — deduping them against each other would hide one behind the other.
    val lineages = durable
      .groupBy(o =>
        (o.run.manifest.workDir, o.session.agent, o.session.sessionName)
      )
      .values
      .map(_.sortBy(recency).reverse)
      .toList
    val primary = lineages.map(_.head).sortBy(recency).reverse
    val earlier = lineages.flatMap(_.tail).sortBy(recency).reverse
    val oneShotSorted = oneShot.sortBy(recency).reverse

    // Which directory a session is in only matters once the listing spans more
    // than one, and then it is the only thing telling two rows apart.
    val where =
      if runs.map(_.manifest.workDir).distinct.sizeIs > 1 then
        (o: Occurrence) => s" @${lastSegment(o.run.manifest.workDir)}"
      else (_: Occurrence) => ""

    val primaryRows = primary.map(o => resumeRow(o, primaryLabel(o) + where(o)))
    val earlierRows =
      if expanded then
        earlier.map(o => resumeRow(o, earlierLabel(o) + where(o)))
      else expanderRow(earlier.size, "earlier occurrence")
    val oneShotRows =
      if expanded then
        oneShotSorted.map(o => resumeRow(o, oneShotLabel(o) + where(o)))
      else
        expanderRow(
          oneShotSorted.size,
          "one-shot session",
          " (reviews, plan steps)"
        )

    primaryRows ++ earlierRows ++ oneShotRows

  /** A kind this build doesn't know (a newer build's manifest) is grouped with
    * the one-shots: those rows are listed as they come, while the durable half
    * is deduped by a `sessionName` such a session may not have.
    */
  private def isDurable(o: Occurrence): Boolean = o.session.kind match
    case ManifestSessionKind.Durable                                  => true
    case ManifestSessionKind.OneShot | ManifestSessionKind.Unknown(_) => false

  private def recency(o: Occurrence): Instant = o.session.lastActiveAt

  /** A recorded `workDir`'s final segment, for telling two same-named lineages
    * apart. String-sliced, not `os.Path`-parsed: the value is manifest content,
    * and a hand-edited one need not be an absolute path.
    */
  private def lastSegment(workDir: String): String =
    workDir.split('/').filter(_.nonEmpty).lastOption.getOrElse(workDir)

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
    * only). Falls back to the agent name if a malformed manifest somehow has a
    * [[ManifestSessionKind.Durable]] session without a `sessionName`.
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

  /** A picker row resolved for a selector: its selection, or a refusal reading
    * `<notResumable> — <disabledReason>`.
    */
  private def resolveRow(
      choice: Choice[PickerRow],
      notResumable: String,
      onShowMore: Either[String, SessionSelection]
  ): Either[String, SessionSelection] =
    choice.value match
      case PickerRow.Resume(selection) =>
        choice.disabledReason.map(r => s"$notResumable — $r").toLeft(selection)
      case PickerRow.ShowMore => onShowMore

  private[shell] def newestDurableSelection(
      runs: List[RecordedRun]
  ): Either[String, SessionSelection] =
    sessionRows(runs, expanded = false).headOption match
      case None => Left("no sessions recorded yet")
      case Some(choice) =>
        resolveRow(
          choice,
          "can't resume the newest session",
          // reachable: with no durable lineages, the collapsed listing's head
          // is the one-shot expander row
          Left(
            "no durable session to continue yet — see `orca continue --list`"
          )
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
        resolveRow(
          choice,
          s"session $index isn't resumable",
          // unreachable: withoutExpanders already dropped every ShowMore row
          Left(s"no session at index $index")
        )

  private[shell] def selectByName(
      runs: List[RecordedRun],
      name: String
  ): Either[String, SessionSelection] =
    val notFound =
      Left(s"no session named '$name' found — see `orca continue --list`")
    val matches =
      withoutExpanders(sessionRows(runs, expanded = false)).collect:
        case choice @ Choice(PickerRow.Resume(selection), _, _)
            if selection.session.sessionName.contains(name) =>
          (choice, selection)
    matches match
      case Nil => notFound
      case (choice, _) :: Nil =>
        resolveRow(
          choice,
          s"session '$name' isn't resumable",
          // unreachable: withoutExpanders already dropped every ShowMore row
          notFound
        )
      case multiple =>
        // Same name in two worktrees matches on one agent, so naming agents
        // alone would read as "ambiguous — matches agents: coder".
        val agents = multiple.map(_._2.session.agent).distinct
        val detail =
          if agents.sizeIs > 1 then s"agents: ${agents.mkString(", ")}"
          else
            val dirs = multiple.map(_._2.manifest.workDir).distinct
            s"working directories: ${dirs.mkString(", ")}"
        Left(s"'$name' is ambiguous — matches $detail")

  /** [[sessionRows]]'s rows, dropping the "show more" expanders — never present
    * for [[SessionSelection]] callers (`selectByIndex` reads the fully expanded
    * listing, `selectByName` only ever resolves to an actual session or fails).
    */
  private[shell] def withoutExpanders(
      rows: List[Choice[PickerRow]]
  ): List[Choice[PickerRow]] =
    rows.filter(_.value != PickerRow.ShowMore)
