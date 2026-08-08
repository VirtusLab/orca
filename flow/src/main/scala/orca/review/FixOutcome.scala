package orca.review

import orca.agents.{Announce, JsonData, given}
import orca.plan.Title

/** What the fixing agent reports back per iteration: the issues it actually
  * fixed in the code, and the issues it chose not to fix along with a reason.
  * Each is named by the per-turn key [[FixRequest]] gave it, followed by its
  * title.
  *
  * The prompt requires every input issue to land in exactly one list, but a
  * fallible agent forgets or paraphrases, so the raw reply is never read
  * directly: [[FixOutcome.reconcile]] maps it back onto the issues that were
  * handed out.
  */
case class FixOutcome(
    fixed: List[Title],
    ignored: List[IgnoredIssue]
) derives JsonData

/** A [[FixOutcome]] resolved against the issues the fixer was handed, so every
  * handed title is in exactly one bucket and no echo is counted twice.
  *
  * `unaccounted` is what came back in neither list, as bare titles: the reason
  * belongs to the exit that records them, not to the reconciliation. The fix
  * prompt asks for every issue to be accounted for; when one isn't, it is still
  * open, so a loop halting here records it rather than dropping it. A loop that
  * goes on to re-evaluate ignores it by name instead: the reviewer's persistent
  * session re-reports a forgotten issue that is still real, and recording it
  * here would report issues the next round went on to fix.
  *
  * `unresolvedEchoes` is what the fixer named that matched no handed issue —
  * dropped from the books, and worth announcing, since it means the reply is
  * degraded.
  */
private[review] case class ReconciledFixOutcome(
    fixed: List[Title],
    ignored: List[IgnoredIssue],
    unaccounted: List[Title],
    unresolvedEchoes: List[String]
)

object FixOutcome:
  /** Silent — the fix loop already announces its outcome ("Fixed N, ignored N")
    * per iteration; without this, the raw-payload fallback (ADR 0008) would
    * print the JSON on top of that line, since `FixOutcome` has no other
    * `Announce` instance to resolve to.
    */
  given Announce[FixOutcome] = Announce.from(_ => "")

  /** Resolve `outcome`'s echoed entries back to the issues in `handed`.
    *
    * An echo resolves by the [[FixRequest]] key it starts with, then by exact
    * title, then by a title matched case- and whitespace-insensitively. Each
    * handed issue takes at most one echo (`fixed` wins over `ignored`, since
    * the fix is the stronger claim) and each echo at most one issue, so a
    * paraphrase cannot record one real finding twice.
    */
  private[review] def reconcile(
      handed: List[ReviewIssue],
      outcome: FixOutcome
  ): ReconciledFixOutcome =
    val keyed = handed.zipWithIndex.map((i, n) => (FixRequest.key(n), i))

    def resolve(echo: String): Option[ReviewIssue] =
      val text = echo.trim
      keyed
        .collectFirst { case (k, i) if startsWithKey(text, k) => i }
        .orElse(handed.find(_.title.value == text))
        .orElse(handed.find(i => normalised(i.title.value) == normalised(text)))

    // Buckets are keyed by title throughout, so two reviewers reporting the
    // same title cannot land one copy in `ignored` and the other in
    // `unaccounted`.
    val fixedTitles = outcome.fixed.flatMap(t => resolve(t.value)).map(_.title)
    val ignoredEntries = outcome.ignored
      .flatMap(entry => resolve(entry.title.value).map(_.title -> entry.reason))
      .filterNot((title, _) => fixedTitles.contains(title))
      .distinctBy((title, _) => title)
    val accounted = (fixedTitles ++ ignoredEntries.map((t, _) => t)).toSet
    val echoes =
      outcome.fixed.map(_.value) ++ outcome.ignored.map(_.title.value)

    ReconciledFixOutcome(
      fixed = fixedTitles.distinct,
      ignored = ignoredEntries.map(IgnoredIssue(_, _)),
      unaccounted = handed.map(_.title).distinct.filterNot(accounted.contains),
      unresolvedEchoes = echoes.filter(resolve(_).isEmpty)
    )

  // A key only matches when the echo doesn't continue it with another letter or
  // digit, so `I1` claims neither the reply naming `I10` nor one opening with
  // an unrelated word like `I2C bus timing`.
  private def startsWithKey(echo: String, key: String): Boolean =
    echo.startsWith(key) &&
      (echo.length == key.length || !echo.charAt(key.length).isLetterOrDigit)

  private def normalised(title: String): String =
    title.trim.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ")
