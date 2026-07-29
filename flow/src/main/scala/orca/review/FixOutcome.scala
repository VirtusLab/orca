package orca.review

import orca.agents.{Announce, JsonData, given}
import orca.plan.Title

/** What the fixing agent reports back per iteration: the titles of issues it
  * actually fixed in the code, and the issues it chose not to fix along with a
  * reason. The prompt requires every input issue to land in exactly one list; a
  * title showing up in neither is still open, so when `fixed` is empty (the
  * loop's halt condition) `reviewAndFixLoop` records it as ignored with a
  * "fixer reported no fixes" reason rather than dropping it.
  */
case class FixOutcome(
    fixed: List[Title],
    ignored: List[IgnoredIssue]
) derives JsonData

object FixOutcome:
  /** Silent — the fix loop already announces its outcome ("Fixed N, ignored N")
    * per iteration; without this, the raw-payload fallback (ADR 0008) would
    * print the JSON on top of that line, since `FixOutcome` has no other
    * `Announce` instance to resolve to.
    */
  given Announce[FixOutcome] = Announce.from(_ => "")
