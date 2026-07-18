package orca.review

import orca.agents.{Announce, JsonData}

case class SelectedReviewers(names: List[String]) derives JsonData:
  /** Resolve the picker's reply to roster entries by matching the bare slug.
    * Matching against the handed [[RosterEntry]] list (not raw names) is the
    * hallucinated-picker floor: an invented name that no entry carries matches
    * nothing.
    */
  def pick(all: List[RosterEntry[?]]): List[RosterEntry[?]] =
    all.filter(r => names.contains(r.name))

object SelectedReviewers:
  /** Deliberately silent: the review loop narrates the selection itself
    * ("Running N review agents"), so a summary here would render the picker's
    * raw JSON on top of that line.
    */
  given Announce[SelectedReviewers] = Announce.from(_ => "")
