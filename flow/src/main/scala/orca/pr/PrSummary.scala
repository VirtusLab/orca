package orca.pr

import orca.llm.{Announce, JsonData}

/** What [[summarisePr]] produces: a one-line PR title and a multi-paragraph
  * body. `gh.createPr(title = …, body = …)` accepts the two fields directly, so
  * call sites typically destructure or pass them positionally.
  */
case class PrSummary(title: String, body: String) derives JsonData

object PrSummary:
  /** Silent — the calling stage already names the PR; an `Announce` summary
    * here would just repeat the title.
    */
  given Announce[PrSummary] = Announce.from(_ => "")
