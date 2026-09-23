package orca.shell.sessions

import orca.StagePath
import orca.agents.{BackendTag, SessionKey}
import orca.runner.manifest.{ManifestSession, SessionKind}
import orca.settings.AgentSpec
import orca.shell.ui.Choice

import java.time.Instant

/** The continue-a-session picker (ADR 0021 §8): groups, sorts, and labels the
  * sessions across every recorded attempt into selectable rows, and resolves a
  * CLI-style selector (index / name / branch / newest) to a
  * [[SessionSelection]]. Shared by the interactive menu
  * (`Main.continueSession`) and the CLI's `continue` command.
  */
private[shell] object SessionPicker:

  /** One session occurrence paired with the attempt it came from — the unit
    * [[sessionRows]] groups, sorts, and labels. Carrying the whole
    * [[RecordedAttempt]] (not just its `crashed` flag) keeps
    * [[SessionSelection]] constructible straight from an occurrence.
    */
  private case class Occurrence(
      attempt: RecordedAttempt,
      session: ManifestSession
  )

  /** One outcome of the continue-session picker: either resume a specific
    * session, or re-render the picker with the collapsed groups (older lineage
    * occurrences, ephemeral sessions) expanded.
    */
  private[shell] enum PickerRow:
    case Resume(selection: SessionSelection)
    case ShowMore

  /** Builds the continue-session picker's rows (ADR 0021 §8): durable lineages
    * first, ephemeral sessions last, with two kinds of rows collapsed by
    * default behind an expander.
    *
    * A durable lineage is an `(agent, minted key)` pair within one run — every
    * occurrence of it across every attempt of that run in `attempts`, not just
    * the newest, since a lineage's key is stable across attempts (each process
    * mints a fresh `clientId`/`wireId` but reuses the same `agent.session(name,
    * ...)` key) while one attempt's durable session always upserts onto one
    * manifest row. The minting stage is part of the key, so the per-task
    * `implementer` sessions of one attempt are separate lineages rather than
    * occurrences of each other. Only the occurrence with the max `lastActiveAt`
    * is shown (marked `★ ... — latest`, the primary continuation target); the
    * rest collapse behind a "show N earlier occurrences" row. Ephemeral
    * sessions (Plan-stage calls, reviewer-selection calls, reviewer `chat()`
    * runs) are never deduped — each is a genuinely distinct fresh session — but
    * collapse behind a single "show N ephemeral sessions" row, since these are
    * the rows that otherwise flood the picker with same-named, low-value
    * entries.
    *
    * Two lineages that differ only in their minting stage otherwise render
    * identically, since a row shows the session's bare name and its LAST ACTIVE
    * stage; [[mintedInTag]] appends the minting stage to exactly those rows.
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
      attempts: List[RecordedAttempt],
      expanded: Boolean
  ): List[Choice[PickerRow]] =
    val occurrences =
      for
        attempt <- attempts
        session <- attempt.manifest.sessions
      yield Occurrence(attempt, session)
    val (durable, ephemeral) =
      occurrences.partition(_.session.kind == SessionKind.Durable)

    val lineages = durable
      .groupBy(lineageKey)
      .values
      .map(_.sortBy(recency).reverse)
      .toList
    val primary = lineages.map(_.head).sortBy(recency).reverse
    val earlier = lineages.flatMap(_.tail).sortBy(recency).reverse
    val ephemeralSorted = ephemeral.sortBy(recency).reverse

    val tag = dirTag(attempts)
    val where = (o: Occurrence) =>
      tag(o.attempt.manifest.workDir, o.attempt.manifest.branch)
    val primaryLabels = primary.map(o => (o, primaryLabel(o) + where(o)))
    val mintedIn = mintedInTag(primaryLabels)

    val primaryRows =
      primaryLabels.map((o, label) => resumeRow(o, label + mintedIn(o)))
    val earlierRows =
      if expanded then
        earlier.map(o => resumeRow(o, earlierLabel(o) + where(o) + mintedIn(o)))
      else expanderRow(earlier.size, "earlier occurrence")
    val ephemeralRows =
      if expanded then
        ephemeralSorted.map(o => resumeRow(o, ephemeralLabel(o) + where(o)))
      else
        expanderRow(
          ephemeralSorted.size,
          "ephemeral session",
          " (reviews, plan steps)"
        )

    primaryRows ++ earlierRows ++ ephemeralRows

  private def recency(o: Occurrence): Instant = o.session.lastActiveAt

  /** What makes two occurrences the same durable conversation. Flow session
    * keys are static ("implementer" on the same task in every run), so the key
    * alone would merge unrelated runs: the working directory separates them
    * because harness sessions are cwd-scoped, and the bound branch separates
    * runs in one directory while grouping the resumed attempts of one run.
    */
  private def lineageKey(
      o: Occurrence
  ): (String, Option[String], String, Option[SessionKey]) =
    (
      o.attempt.manifest.workDir,
      o.attempt.manifest.branch,
      o.session.agent,
      o.session.minted
    )

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
      primaryLabels: List[(Occurrence, String)]
  ): Occurrence => String =
    val ambiguous = primaryLabels
      .groupBy((_, label) => label)
      .values
      .filter(_.sizeIs > 1)
      .flatten
      .map((o, _) => lineageKey(o))
      .toSet
    o =>
      if !ambiguous(lineageKey(o)) then ""
      else
        o.session.minted.map(_.stage) match
          case Some(StagePath.Stage(id)) => s" (minted in ${id.value})"
          case _                         => " (minted in the flow body)"

  /** How a row says which tree its session is in, given the attempts being
    * rendered and the row's `(workDir, branch)`: nothing when the attempts
    * share one `workDir`, or when the row's branch already identifies it —
    * recorded, and by attempts from only one `workDir`. Otherwise a ` @<dir>`
    * suffix. The interactive picker and `orca continue --list` both call this
    * over the same attempts, so the two surfaces cannot drift on either the
    * rule or the marker's shape.
    */
  private[shell] def dirTag(
      attempts: List[RecordedAttempt]
  ): (String, Option[String]) => String =
    val manifests = attempts.map(_.manifest)
    if manifests.map(_.workDir).distinct.sizeIs <= 1 then (_, _) => ""
    else
      val dirsPerBranch = manifests
        .flatMap(m => m.branch.map(_ -> m.workDir))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.distinct.size)
        .toMap
      (workDir, branch) =>
        if branch.exists(dirsPerBranch(_) == 1) then ""
        else s" @${lastSegment(workDir)}"

  /** A recorded `workDir`'s final segment. String-sliced, not `os.Path`-parsed:
    * the value is manifest content, and a hand-edited one need not be an
    * absolute path.
    */
  private def lastSegment(workDir: String): String =
    workDir.split('/').filter(_.nonEmpty).lastOption.getOrElse(workDir)

  private def resumeRow(o: Occurrence, label: String): Choice[PickerRow] =
    Choice(
      PickerRow.Resume(
        SessionSelection(o.attempt.manifest, o.session, o.attempt.crashed)
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

  /** `★ <session> — latest (stage: <stage>) [<harness>] on <branch>`, or `(no
    * stage yet)` when the durable session hasn't entered a stage (rare — custom
    * flows only); the branch segment is omitted when the attempt recorded none.
    */
  private def primaryLabel(o: Occurrence): String =
    val name = displayName(o.session)
    val stage = o.session.stage.fold("no stage yet")(s => s"stage: $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.attempt.crashed then " (crashed)" else ""
    s"★ $name — latest ($stage) [$harness]${onBranch(o)}$crashedSuffix"

  /** `<session> — stage <stage> [<harness>] (earlier occurrence) on <branch>`,
    * shown only when the picker is expanded; the branch segment as in
    * [[primaryLabel]].
    */
  private def earlierLabel(o: Occurrence): String =
    val name = displayName(o.session)
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.attempt.crashed then " (crashed)" else ""
    s"$name$stage [$harness] (earlier occurrence)${onBranch(o)}$crashedSuffix"

  /** `<agent> (<role>) — stage <stage> [<harness>] (ephemeral) on <branch>`,
    * omitting the role/stage/branch segments when absent; shown only when the
    * picker is expanded.
    */
  private def ephemeralLabel(o: Occurrence): String =
    val role = o.session.role.fold("")(r => s" ($r)")
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.attempt.crashed then " (crashed)" else ""
    s"${o.session.agent}$role$stage [$harness] (ephemeral)${onBranch(o)}$crashedSuffix"

  private def onBranch(o: Occurrence): String =
    o.attempt.manifest.branch.fold("")(b => s" on $b")

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
    * durable lineage, an all-digits selector picks that 1-based row from the
    * full (expanded) listing — even when a branch has that name — and anything
    * else is matched exactly against durable sessions' names and recorded
    * branches. A branch picks its most recently active lineage. A selector
    * matching both kinds, or a branch in more than one working directory, is
    * refused rather than guessed. Matching never uses the stage a session was
    * minted in, so resuming never asks a user to spell out a stage path id.
    */
  private[shell] def resolveSelection(
      attempts: List[RecordedAttempt],
      selector: Option[String]
  ): Either[String, SessionSelection] =
    selector match
      case None                  => newestDurableSelection(attempts)
      case Some(s) if isIndex(s) => selectByDigits(attempts, s)
      case Some(s)               => selectByNameOrBranch(attempts, s)

  // Not `toIntOption`: it also accepts a sign (`+1`), which reads as a name.
  private def isIndex(s: String): Boolean = s.nonEmpty && s.forall(_.isDigit)

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
      attempts: List[RecordedAttempt]
  ): Either[String, SessionSelection] =
    sessionRows(attempts, expanded = false).headOption match
      case None => Left("no sessions recorded yet")
      case Some(choice) =>
        resolveRow(
          choice,
          "can't resume the newest session",
          // reachable: with no durable lineages, the collapsed listing's head
          // is the ephemeral expander row
          Left(
            "no durable session to continue yet — see `orca continue --list`"
          )
        )

  private def selectByDigits(
      attempts: List[RecordedAttempt],
      digits: String
  ): Either[String, SessionSelection] =
    digits.toIntOption match
      case Some(index) => selectByIndex(attempts, index)
      // too large for an Int, so past the end of any listing
      case None => Left(noSessionAt(digits, indexedRows(attempts).size))

  private[shell] def selectByIndex(
      attempts: List[RecordedAttempt],
      index: Int
  ): Either[String, SessionSelection] =
    val rows = indexedRows(attempts)
    rows.lift(index - 1) match
      case None => Left(noSessionAt(index.toString, rows.size))
      case Some(choice) =>
        resolveRow(
          choice,
          s"session $index isn't resumable",
          // unreachable: withoutExpanders already dropped every ShowMore row
          Left(s"no session at index $index")
        )

  /** The rows an index selector counts: the listing `orca continue --list`
    * prints.
    */
  private def indexedRows(
      attempts: List[RecordedAttempt]
  ): List[Choice[PickerRow]] =
    withoutExpanders(sessionRows(attempts, expanded = true))

  private def noSessionAt(index: String, rowCount: Int): String =
    s"no session at index $index — see `orca continue --list` (1-$rowCount)"

  private def selectByNameOrBranch(
      attempts: List[RecordedAttempt],
      selector: String
  ): Either[String, SessionSelection] =
    val rows = durableRows(attempts)
    val byName = rows.filter((_, s) => isNamed(s, selector))
    val byBranch = rows.filter((_, s) => s.manifest.branch.contains(selector))
    (byName, byBranch) match
      case (Nil, Nil) =>
        Left(notFound(selector) + branchSuggestions(rows, selector))
      case (_, Nil) => resolveByName(selector, byName)
      case (Nil, _) => resolveByBranch(selector, byBranch)
      case _ =>
        Left(s"'$selector' names both a session and a branch; $pickFromList")

  /** The rows a name or branch selector can match: one per durable lineage,
    * each paired with its selection.
    */
  private def durableRows(
      attempts: List[RecordedAttempt]
  ): List[(Choice[PickerRow], SessionSelection)] =
    withoutExpanders(sessionRows(attempts, expanded = false)).collect:
      case choice @ Choice(PickerRow.Resume(selection), _, _) =>
        (choice, selection)

  private def isNamed(selection: SessionSelection, name: String): Boolean =
    selection.session.minted.exists(_.name == name)

  private def notFound(name: String): String =
    s"no session named '$name' found — see `orca continue --list`"

  /** Resolves non-empty `matches` for a name selector. */
  private def resolveByName(
      name: String,
      matches: List[(Choice[PickerRow], SessionSelection)]
  ): Either[String, SessionSelection] =
    // Ambiguity is decided per (working directory, agent), not per row: within
    // one of those, the rows differ only by their sessions' minting stage —
    // a path id no user should have to spell out — so `continue <name>` takes
    // the most recent, as it does when there is only one.
    val contexts =
      matches.map((_, s) => (s.manifest.workDir, s.session.agent)).distinct
    if contexts.sizeIs > 1 then
      val agents = matches.map(_._2.session.agent).distinct
      // Same name in two worktrees matches on one agent, so naming agents alone
      // would read as "ambiguous — matches agents: coder".
      val where =
        if agents.sizeIs > 1 then s"agents: ${agents.mkString(", ")}"
        else workDirsOf(matches)
      Left(ambiguity(selector = name, where = where))
    else resolveNewest(matches, s"session '$name' isn't resumable")

  private def resolveByBranch(
      branch: String,
      matches: List[(Choice[PickerRow], SessionSelection)]
  ): Either[String, SessionSelection] =
    // One branch in two working directories is two unrelated runs (harness
    // sessions are cwd-scoped), so the newest of them would be a guess.
    if matches.map(_._2.manifest.workDir).distinct.sizeIs > 1 then
      Left(ambiguity(selector = branch, where = workDirsOf(matches)))
    else
      resolveNewest(
        matches,
        s"the newest session on branch '$branch' isn't resumable"
      )

  /** The most recently active of non-empty `matches`, or a refusal starting
    * with `notResumable` when that row is disabled.
    */
  private def resolveNewest(
      matches: List[(Choice[PickerRow], SessionSelection)],
      notResumable: String
  ): Either[String, SessionSelection] =
    val (newest, _) = matches.maxBy((_, s) => s.session.lastActiveAt)
    resolveRow(
      newest,
      notResumable,
      // unreachable: withoutExpanders already dropped every ShowMore row
      Left(notResumable)
    )

  private def workDirsOf(
      matches: List[(Choice[PickerRow], SessionSelection)]
  ): String =
    s"working directories: ${matches.map(_._2.manifest.workDir).distinct.mkString(", ")}"

  /** Why a selector won't guess between the contexts named by `where`, and what
    * to do instead.
    */
  private def ambiguity(selector: String, where: String): String =
    s"'$selector' is ambiguous — matches $where; $pickFromList"

  private val pickFromList: String =
    "run `orca continue --list` and pick one by its number"

  /** `; did you mean: b1, b2` over the branches of `rows` containing
    * `selector`, most recently active first, or nothing when none do. Taken
    * from the rows a branch selector matches, so every suggestion resolves.
    */
  private def branchSuggestions(
      rows: List[(Choice[PickerRow], SessionSelection)],
      selector: String
  ): String =
    rows
      .map(_._2)
      .sortBy(_.session.lastActiveAt)
      .reverse
      .flatMap(_.manifest.branch)
      .filter(_.contains(selector))
      .distinct match
      case Nil      => ""
      case branches => s"; did you mean: ${branches.mkString(", ")}"

  /** [[sessionRows]]'s rows, dropping the "show more" expanders — never present
    * for [[SessionSelection]] callers (`selectByIndex` reads the fully expanded
    * listing, name and branch selectors go through `durableRows` and only ever
    * resolve to an actual session or fail).
    */
  private[shell] def withoutExpanders(
      rows: List[Choice[PickerRow]]
  ): List[Choice[PickerRow]] =
    rows.filter(_.value != PickerRow.ShowMore)
