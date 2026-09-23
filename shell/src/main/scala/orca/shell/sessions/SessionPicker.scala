package orca.shell.sessions

import orca.StagePath
import orca.settings.AgentSpec
import orca.shell.ui.Choice

/** The continue-a-session picker (ADR 0021 §8): labels a [[SessionIndex]]'s
  * sessions as selectable rows for the interactive menu
  * (`Main.continueSession`).
  */
private[shell] object SessionPicker:

  /** One outcome of the continue-session picker: either resume a specific
    * session, or re-render the picker with the collapsed groups (older lineage
    * occurrences, ephemeral sessions) expanded.
    */
  private[shell] enum PickerRow:
    case Resume(selection: SessionSelection)
    case ShowMore

  /** The continue-session picker's rows (ADR 0021 §8): each durable lineage's
    * latest occurrence (marked `★ ... — latest`, the primary continuation
    * target), then two groups collapsed by default behind an expander row: the
    * lineages' earlier occurrences ("show N earlier occurrences") and the
    * ephemeral sessions ("show N ephemeral sessions") — the rows that otherwise
    * flood the picker with same-named, low-value entries.
    *
    * Two lineages that differ only in their minting stage otherwise render
    * identically, since a row shows the session's bare name and its LAST ACTIVE
    * stage; [[mintedInTag]] appends the minting stage to exactly those rows.
    *
    * `expanded` reveals both collapsed groups in place, in
    * [[SessionIndex.listing]] order. Disabling a row previews only what
    * [[ResumeCommand.staticGate]] can tell without a live harness call: a
    * wireId-less session. The checks that need the manifest's `workDir` or a
    * live call — gemini's `gemini --list-sessions` index, pi's session dir —
    * are deferred to selection, in [[orca.shell.actions.SessionAction.resume]],
    * so those rows stay enabled pending that later check.
    */
  private[shell] def sessionRows(
      index: SessionIndex,
      expanded: Boolean
  ): List[Choice[PickerRow]] =
    val tag = SessionNaming.dirTag(index)
    val where = (s: SessionSelection) =>
      tag(s.manifest.workDir, s.manifest.branch)
    val primary = index.latest
    val primaryLabels = primary.map(s => (s, primaryLabel(s) + where(s)))
    val mintedIn = mintedInTag(primaryLabels)

    val primaryRows =
      primaryLabels.map((s, label) => resumeRow(s, label + mintedIn(s)))
    val earlier = index.earlier
    val earlierRows =
      if expanded then
        earlier.map(s => resumeRow(s, earlierLabel(s) + where(s) + mintedIn(s)))
      else expanderRow(earlier.size, "earlier occurrence")
    val ephemeralRows =
      if expanded then
        index.ephemeral.map(s => resumeRow(s, ephemeralLabel(s) + where(s)))
      else
        expanderRow(
          index.ephemeral.size,
          "ephemeral session",
          " (reviews, plan steps)"
        )

    primaryRows ++ earlierRows ++ ephemeralRows

  /** How a row says which stage minted its session, given the primary rows and
    * the labels they would otherwise carry: nothing, unless another lineage
    * renders to the very same label, in which case the minting stage is the
    * only half of the key left to tell them apart.
    *
    * It is shown nowhere else because it is a path id (`Task: add multiply#0`)
    * rather than prose. Every row of an affected lineage carries it, primary
    * and earlier alike.
    */
  private def mintedInTag(
      primaryLabels: List[(SessionSelection, String)]
  ): SessionSelection => String =
    val ambiguous = primaryLabels
      .groupBy((_, label) => label)
      .values
      .filter(_.sizeIs > 1)
      .flatten
      .flatMap((s, _) => LineageKey.of(s))
      .toSet
    s =>
      LineageKey
        .of(s)
        .filter(ambiguous)
        .fold(""): key =>
          key.minted.stage match
            case stage: StagePath.Stage => s" (minted in ${stage.display})"
            case _                      => " (minted in the flow body)"

  private def resumeRow(
      s: SessionSelection,
      label: String
  ): Choice[PickerRow] =
    Choice(
      PickerRow.Resume(s),
      label,
      disabledReason = ResumeCommand.staticGate(s.session).left.toOption
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

  /** `★ <session> — latest (stage: <stage>) [<harness>] on <branch>`, or `(no
    * stage yet)` when the durable session hasn't entered a stage (rare — custom
    * flows only); the branch segment is omitted when the attempt recorded none.
    */
  private def primaryLabel(selection: SessionSelection): String =
    val name = SessionNaming.displayName(selection.session)
    val stage = selection.session.stage.fold("no stage yet")(s => s"stage: $s")
    val harness = AgentSpec.harnessNameFor(selection.session.harness)
    val crashedSuffix = if selection.crashed then " (crashed)" else ""
    s"★ $name — latest ($stage) [$harness]${onBranch(selection)}$crashedSuffix"

  /** `<session> — stage <stage> [<harness>] (earlier occurrence) on <branch>`,
    * shown only when the picker is expanded; the branch segment as in
    * [[primaryLabel]].
    */
  private def earlierLabel(selection: SessionSelection): String =
    val name = SessionNaming.displayName(selection.session)
    val stage = selection.session.stage.fold("")(s => s" — stage $s")
    val harness = AgentSpec.harnessNameFor(selection.session.harness)
    val crashedSuffix = if selection.crashed then " (crashed)" else ""
    s"$name$stage [$harness] (earlier occurrence)${onBranch(selection)}$crashedSuffix"

  /** `<agent> (<role>) — stage <stage> [<harness>] (ephemeral) on <branch>`,
    * omitting the role/stage/branch segments when absent; shown only when the
    * picker is expanded.
    */
  private def ephemeralLabel(selection: SessionSelection): String =
    val role = selection.session.role.fold("")(r => s" ($r)")
    val stage = selection.session.stage.fold("")(s => s" — stage $s")
    val harness = AgentSpec.harnessNameFor(selection.session.harness)
    val crashedSuffix = if selection.crashed then " (crashed)" else ""
    s"${selection.session.agent}$role$stage [$harness] (ephemeral)${onBranch(selection)}$crashedSuffix"

  private def onBranch(selection: SessionSelection): String =
    selection.manifest.branch.fold("")(b => s" on $b")
