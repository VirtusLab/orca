package orca.review

import orca.agents.{JsonData, Agent}

case class SelectedReviewers(names: List[String]) derives JsonData:
  /** Resolve the picker's reply to tools by matching the bare slug — a
    * reviewer's identity, and exactly what the picker is shown and asked to
    * echo. The `reviewer: ` cost-attribution prefix never reaches the picker,
    * so it plays no part in selection.
    */
  def pick(all: List[Agent[?]]): List[Agent[?]] =
    all.filter(r => names.contains(r.name))
