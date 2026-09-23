package orca.review

import orca.agents.{Announce, JsonData, given}
import orca.plan.Title

/** What the fixing agent reports back per iteration: the findings it actually
  * fixed in the code, and the findings it chose not to fix along with a reason.
  * Each is named by the per-turn key [[FixRequest]] gave it, followed by its
  * title.
  *
  * The prompt requires every input finding to land in exactly one list, but a
  * fallible agent forgets or paraphrases, so the raw reply is never read
  * directly: [[FixOutcome.reconcile]] maps it back onto the findings that were
  * handed out.
  */
case class FixOutcome(
    fixed: List[Title],
    declined: List[DeclinedFinding]
) derives JsonData

/** One finding the fixer refused on a turn, and the reason it gave. The wire
  * shape the agent fills, so it holds only what the agent can know: where the
  * finding points is the reviewer's, and [[FixOutcome.reconcile]] puts it back
  * when it resolves the echo to the [[ReviewFinding]] that was handed out.
  */
case class DeclinedFinding(title: Title, reason: String) derives JsonData

/** A [[FixOutcome]] resolved against the findings the fixer was handed, so
  * every handed finding is in exactly one bucket and no echo is counted twice.
  *
  * `declined` and `unaccounted` are both whole findings once resolved, so a
  * caller recording either as an [[OpenFinding]] still knows its id and where
  * it points. `unaccounted` is what came back in neither list; it carries no
  * reason, which belongs to the exit that records them, not to the
  * reconciliation. The fix prompt asks for every finding to be accounted for;
  * when one isn't, it is still open, so a loop halting here records it rather
  * than dropping it. A loop that goes on to re-evaluate leaves it out instead:
  * the reviewer's persistent session re-reports a forgotten finding that is
  * still real, and recording it here would report findings the next round went
  * on to fix.
  *
  * `unresolvedEchoes` is what the fixer named that matched no handed finding —
  * dropped from the books, and worth announcing, since it means the reply is
  * degraded.
  */
private[review] case class ReconciledFixOutcome(
    fixed: List[FindingId],
    declined: List[OpenFinding],
    unaccounted: List[IdentifiedFinding],
    unresolvedEchoes: List[String]
)

object FixOutcome:
  /** Silent — the fix loop already announces its outcome ("Fixed N, declined
    * N") per iteration; without this, the raw-payload fallback (ADR 0008) would
    * print the JSON on top of that line, since `FixOutcome` has no other
    * `Announce` instance to resolve to.
    */
  given Announce[FixOutcome] = Announce.from(_ => "")

  /** Resolve `outcome`'s echoed entries back to the findings in `handed`.
    *
    * An echo resolves by the [[FixRequest]] key it starts with, then by exact
    * title, then by a title matched case- and whitespace-insensitively, and
    * last by the sole title the echo extends past a non-alphanumeric separator
    * — the shape of a keyless echo carrying the fix prompt's "which alternative
    * was taken" suffix. A key names one finding; a title names every handed
    * finding with it, as the echo cannot tell them apart. Each handed finding
    * takes at most one verdict (`fixed` wins over `declined`, since the fix is
    * the stronger claim), so a paraphrase cannot record one real finding twice.
    */
  private[review] def reconcile(
      handed: List[IdentifiedFinding],
      outcome: FixOutcome
  ): ReconciledFixOutcome =
    def titled(normalised: String): List[IdentifiedFinding] =
      handed.filter(h => normalisedTitle(h.finding.title.value) == normalised)

    // Titles can themselves contain " — ", so the echo is prefix-matched
    // against each full title rather than split at a separator; an echo
    // extending more than one distinct title stays unresolved rather than
    // guessed at.
    def bySuffixedTitle(text: String): List[IdentifiedFinding] =
      val echoNorm = normalisedTitle(text)
      handed
        .map(h => normalisedTitle(h.finding.title.value))
        .filter: t =>
          echoNorm.length > t.length && echoNorm.startsWith(t) &&
            !echoNorm.charAt(t.length).isLetterOrDigit
        .distinct match
        case List(only) => titled(only)
        case _          => Nil

    // The first rule that matches anything decides.
    def resolve(echo: String): List[IdentifiedFinding] =
      val text = echo.trim
      (handed.find(h => startsWithKey(text, h.keyed.key)).toList
        #:: handed.filter(_.finding.title.value == text)
        #:: titled(normalisedTitle(text))
        #:: bySuffixedTitle(text)
        #:: LazyList.empty).find(_.nonEmpty).getOrElse(Nil)

    // Buckets are keyed by id throughout, so two reviewers reporting the same
    // finding cannot land one copy in `declined` and the other in
    // `unaccounted`.
    val fixedIds = outcome.fixed.flatMap(t => resolve(t.value)).map(_.id)
    val declinedEntries = outcome.declined
      .flatMap(entry => resolve(entry.title.value).map(_ -> entry.reason))
      .filterNot((h, _) => fixedIds.contains(h.id))
      .distinctBy((h, _) => h.id)
    val accounted = (fixedIds ++ declinedEntries.map((h, _) => h.id)).toSet
    val echoes =
      outcome.fixed.map(_.value) ++ outcome.declined.map(_.title.value)

    ReconciledFixOutcome(
      fixed = fixedIds.distinct,
      declined =
        declinedEntries.map((h, reason) => h.open(OpenReason.Declined(reason))),
      unaccounted = handed.distinctBy(_.id).filterNot(h => accounted(h.id)),
      unresolvedEchoes = echoes.filter(resolve(_).isEmpty)
    )

  // A key only matches when the echo doesn't continue it with another digit —
  // otherwise `I1.1` would claim a reply naming `I1.10` — or with a letter.
  private def startsWithKey(echo: String, key: String): Boolean =
    echo.startsWith(key) &&
      (echo.length == key.length || !echo.charAt(key.length).isLetterOrDigit)
