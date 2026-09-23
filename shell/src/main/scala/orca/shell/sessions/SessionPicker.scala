package orca.shell.sessions

import orca.StagePath
import orca.runner.manifest.ManifestSession
import orca.settings.AgentSpec
import orca.shell.ui.Choice

/** The continue-a-session picker (ADR 0021 §8): labels a [[SessionIndex]]'s
  * sessions as selectable rows for the interactive menu
  * (`Main.continueSession`), and the naming it shares with `continue --list`.
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
    val tag = dirTag(index)
    val where = (s: SessionSelection) =>
      tag(s.manifest.workDir, s.manifest.branch)
    val primary = index.lineages.map(_.latest)
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
            case StagePath.Stage(id) => s" (minted in ${id.value})"
            case _                   => " (minted in the flow body)"

  /** How a row says which tree its session is in, given the index being
    * rendered and the row's `(workDir, branch)`: nothing when the sessions
    * share one `workDir` or the row has a branch, otherwise a ` @<dir>` suffix.
    * The interactive picker and `orca continue --list` both call this over the
    * same index, so the two surfaces cannot drift on either the rule or the
    * marker's shape.
    */
  private[shell] def dirTag(
      index: SessionIndex
  ): (String, Option[String]) => String =
    if index.listing.map(_.manifest.workDir).distinct.sizeIs <= 1 then
      (_, _) => ""
    else
      (workDir, branch) =>
        if branch.isDefined then "" else s" @${lastSegment(workDir)}"

  /** A recorded `workDir`'s final segment. String-sliced, not `os.Path`-parsed:
    * the value is manifest content, and a hand-edited one need not be an
    * absolute path.
    */
  private def lastSegment(workDir: String): String =
    workDir.split('/').filter(_.nonEmpty).lastOption.getOrElse(workDir)

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
  private def primaryLabel(o: SessionSelection): String =
    val name = displayName(o.session)
    val stage = o.session.stage.fold("no stage yet")(s => s"stage: $s")
    val harness = AgentSpec.harnessNameFor(o.session.harness)
    val crashedSuffix = if o.crashed then " (crashed)" else ""
    s"★ $name — latest ($stage) [$harness]${onBranch(o)}$crashedSuffix"

  /** `<session> — stage <stage> [<harness>] (earlier occurrence) on <branch>`,
    * shown only when the picker is expanded; the branch segment as in
    * [[primaryLabel]].
    */
  private def earlierLabel(o: SessionSelection): String =
    val name = displayName(o.session)
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = AgentSpec.harnessNameFor(o.session.harness)
    val crashedSuffix = if o.crashed then " (crashed)" else ""
    s"$name$stage [$harness] (earlier occurrence)${onBranch(o)}$crashedSuffix"

  /** `<agent> (<role>) — stage <stage> [<harness>] (ephemeral) on <branch>`,
    * omitting the role/stage/branch segments when absent; shown only when the
    * picker is expanded.
    */
  private def ephemeralLabel(o: SessionSelection): String =
    val role = o.session.role.fold("")(r => s" ($r)")
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = AgentSpec.harnessNameFor(o.session.harness)
    val crashedSuffix = if o.crashed then " (crashed)" else ""
    s"${o.session.agent}$role$stage [$harness] (ephemeral)${onBranch(o)}$crashedSuffix"

  private def onBranch(o: SessionSelection): String =
    o.manifest.branch.fold("")(b => s" on $b")

  /** How a session reads to a person: the name it was minted under, or the
    * agent name for an ephemeral session. Every shell surface that shows a
    * session calls this, so the picker, `continue --list` and the pre-resume
    * notice cannot drift.
    *
    * The name alone: the key's other half, the minting stage, is a path id
    * rather than prose. A row's `(stage: ...)` segment is a different field —
    * where the session was last active — so [[mintedInTag]] is what appends the
    * minting stage where two rows need it to tell them apart.
    */
  private[shell] def displayName(session: ManifestSession): String =
    session.minted.fold(session.agent)(_.name)
