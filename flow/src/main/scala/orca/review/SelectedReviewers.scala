package orca.review

import orca.agents.JsonData

case class SelectedReviewers(names: List[String]) derives JsonData:
  /** Resolve the picker's reply to roster entries by matching the bare slug — a
    * reviewer's identity, and exactly what the picker is shown and asked to
    * echo. Matching against the handed [[RosterEntry]] list (not raw names) is
    * the hallucinated-picker floor: a name the picker invents that no entry
    * carries simply matches nothing. The `reviewer` cost-attribution role tag
    * never reaches the picker, so it plays no part in selection.
    */
  def pick(all: List[RosterEntry[?]]): List[RosterEntry[?]] =
    all.filter(r => names.contains(r.name))
