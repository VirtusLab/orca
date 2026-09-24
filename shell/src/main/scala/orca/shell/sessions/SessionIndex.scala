package orca.shell.sessions

import orca.agents.SessionKey

/** What makes two recorded sessions the same durable conversation. Flow session
  * keys are static ("implementer" on the same task in every run), so the key
  * alone would merge unrelated runs: the working directory separates them
  * because harness sessions are cwd-scoped, and the bound branch separates runs
  * in one directory while grouping the resumed attempts of one run. The minting
  * stage is part of the key, so the per-task `implementer` sessions of one
  * attempt are separate lineages rather than occurrences of each other.
  */
private[shell] case class LineageKey(
    workDir: String,
    branch: Option[String],
    agent: String,
    minted: SessionKey
)

private[shell] object LineageKey:

  /** The lineage `s` belongs to; `None` for an ephemeral session. */
  def of(s: SessionSelection): Option[LineageKey] =
    s.session.minted.map(
      LineageKey(s.manifest.workDir, s.manifest.branch, s.session.agent, _)
    )

/** Every recorded session, grouped the way `continue` offers them (ADR 0021
  * §8), each list newest first: `latest` holds each durable lineage's most
  * recently active occurrence, `earlier` the lineages' other occurrences, and
  * `ephemeral` the sessions minted under no key (Plan-stage calls,
  * reviewer-selection calls, reviewer `chat()` runs) — each a distinct fresh
  * session, so never grouped. Shared by the interactive picker and `orca
  * continue`, so both show and resolve the same sessions.
  */
private[shell] case class SessionIndex private (
    latest: List[SessionSelection],
    earlier: List[SessionSelection],
    ephemeral: List[SessionSelection]
):

  /** Every session, in the order `continue --list` and the expanded picker show
    * them: each lineage's latest, then the earlier occurrences, then the
    * ephemeral sessions.
    */
  def listing: List[SessionSelection] = latest ++ earlier ++ ephemeral

  /** Resolves a `continue` selector to a session: no selector picks the newest
    * durable lineage, a [[SessionRef]] spelling picks that session, and
    * anything else is matched exactly against durable sessions' names and
    * recorded branches — a name that happens to be spelled like a ref is
    * therefore never matched as a name. A branch picks its most recently active
    * lineage. A selector matching both kinds, or a branch in more than one
    * working directory, is refused rather than guessed. Matching never uses the
    * stage a session was minted in, so resuming never asks a user to spell out
    * a stage path id. A session [[ResumeCommand.staticGate]] rejects is
    * refused.
    */
  def resolve(selector: Option[String]): Either[String, SessionSelection] =
    selector match
      case None => newest
      case Some(s) =>
        SessionRef.parse(s) match
          case Some(ref) => byRef(ref, s)
          case None      => byNameOrBranch(s)

  private def newest: Either[String, SessionSelection] =
    latest.headOption match
      case Some(newest) =>
        requireResumable(newest, "can't resume the newest session")
      case None if ephemeral.nonEmpty =>
        Left("no durable session to continue yet — see `orca continue --list`")
      case None => Left("no sessions recorded yet")

  private def byRef(
      ref: SessionRef,
      spelled: String
  ): Either[String, SessionSelection] =
    listing
      .find(_.ref == ref)
      .toRight(
        s"no session $spelled — it may have been pruned; see `orca continue --list`"
      )
      .flatMap(requireResumable(_, s"session $spelled isn't resumable"))

  private def byNameOrBranch(
      selector: String
  ): Either[String, SessionSelection] =
    val byName = latest.filter(_.session.minted.exists(_.name == selector))
    val byBranch = latest.filter(_.manifest.branch.contains(selector))
    (byName, byBranch) match
      case (Nil, Nil) =>
        Left(
          s"no session or branch named '$selector' found — see `orca continue --list`"
        )
      case (_, Nil) => resolveByName(selector, byName)
      case (Nil, _) => resolveByBranch(selector, byBranch)
      case _ =>
        Left(
          s"'$selector' names both a session and a branch; ${SessionIndex.pickFromList}"
        )

  /** Resolves non-empty `matches` for a name selector. */
  private def resolveByName(
      name: String,
      matches: List[SessionSelection]
  ): Either[String, SessionSelection] =
    // Ambiguity is decided per (working directory, agent), not per lineage:
    // within one of those, lineages differ only by their minting stage — a path
    // id no user should have to spell out — so `continue <name>` takes the most
    // recent, as it does when there is only one.
    val contexts = matches.map(s => (s.manifest.workDir, s.session.agent))
    if contexts.distinct.sizeIs > 1 then
      val agents = matches.map(_.session.agent).distinct
      // Same name in two worktrees matches on one agent, so naming agents alone
      // would read as "ambiguous — matches agents: coder".
      val where =
        if agents.sizeIs > 1 then s"agents: ${agents.mkString(", ")}"
        else SessionIndex.workDirsOf(matches)
      Left(SessionIndex.ambiguity(selector = name, where = where))
    else
      requireResumable(
        matches.maxBy(_.session.lastActiveAt),
        s"session '$name' isn't resumable"
      )

  private def resolveByBranch(
      branch: String,
      matches: List[SessionSelection]
  ): Either[String, SessionSelection] =
    // One branch in two working directories is two unrelated runs (harness
    // sessions are cwd-scoped), so the newest of them would be a guess.
    if matches.map(_.manifest.workDir).distinct.sizeIs > 1 then
      Left(
        SessionIndex.ambiguity(
          selector = branch,
          where = SessionIndex.workDirsOf(matches)
        )
      )
    else
      requireResumable(
        matches.maxBy(_.session.lastActiveAt),
        s"the newest session on branch '$branch' isn't resumable"
      )

  /** `s`, or a refusal reading `<refusalPrefix> — <reason>`. */
  private def requireResumable(
      s: SessionSelection,
      refusalPrefix: String
  ): Either[String, SessionSelection] =
    ResumeCommand
      .staticGate(s.session)
      .left
      .map(reason => s"$refusalPrefix — $reason")
      .map(_ => s)

private[shell] object SessionIndex:

  /** Every session `attempts` recorded, grouped into lineages. */
  def of(attempts: List[RecordedAttempt]): SessionIndex =
    val all =
      for
        attempt <- attempts
        (session, i) <- attempt.manifest.sessions.zipWithIndex
      yield SessionSelection(
        SessionRef(attempt.id, i + 1),
        attempt.manifest,
        session,
        attempt.observedStatus
      )
    val byLineage = all.groupBy(LineageKey.of)
    val lineages = byLineage.collect:
      case (Some(_), occurrences) => newestFirst(occurrences)
    SessionIndex(
      latest = newestFirst(lineages.map(_.head).toList),
      earlier = newestFirst(lineages.flatMap(_.tail).toList),
      ephemeral = newestFirst(byLineage.getOrElse(None, Nil))
    )

  private def newestFirst(
      sessions: List[SessionSelection]
  ): List[SessionSelection] =
    sessions.sortBy(_.session.lastActiveAt).reverse

  private def workDirsOf(matches: List[SessionSelection]): String =
    s"working directories: ${matches.map(_.manifest.workDir).distinct.mkString(", ")}"

  /** Why a selector won't guess between the contexts named by `where`, and what
    * to do instead.
    */
  private def ambiguity(selector: String, where: String): String =
    s"'$selector' is ambiguous — matches $where; $pickFromList"

  private val pickFromList: String =
    "run `orca continue --list` and pick one by its id"
