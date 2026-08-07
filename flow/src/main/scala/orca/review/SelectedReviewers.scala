package orca.review

import orca.agents.{Announce, JsonData}

/** What [[SelectedReviewers.pick]] resolved: the roster entries the picker
  * named, in roster order, and the names that matched none of them. A caller
  * cannot consume a partially-failed pick without naming the residue — a
  * reviewer dropped at selection is out of the whole review, and nothing
  * downstream can put it back.
  */
private[review] case class PickedReviewers(
    entries: List[RosterEntry[?]],
    unresolved: List[String]
)

case class SelectedReviewers(names: List[String]) derives JsonData:
  /** Resolve the picker's reply to roster entries by matching the bare slug.
    * Matching against the handed [[RosterEntry]] list (not raw names) is the
    * hallucinated-picker floor: an invented name that no entry carries matches
    * nothing.
    *
    * Matched trimmed and case-insensitively: a case shift or stray whitespace
    * is what a cheap picker model actually gets wrong, and slugs never collide
    * case-insensitively.
    */
  private[review] def pick(all: List[RosterEntry[?]]): PickedReviewers =
    def key(name: String): String =
      name.trim.toLowerCase(java.util.Locale.ROOT)
    val wanted = names.map(key).toSet
    PickedReviewers(
      entries = all.filter(r => wanted.contains(key(r.name))),
      unresolved = names.filterNot(n => all.exists(r => key(r.name) == key(n)))
    )

object SelectedReviewers:
  /** Deliberately silent: the review loop narrates the selection itself
    * ("Running N review agents"), so a summary here would render the picker's
    * raw JSON on top of that line.
    */
  given Announce[SelectedReviewers] = Announce.from(_ => "")
