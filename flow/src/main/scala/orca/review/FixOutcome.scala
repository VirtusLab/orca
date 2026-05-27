package orca.review

import orca.llm.{JsonData, given}
import orca.plan.Title

/** What the fixing agent reports back per iteration: the titles of issues it
  * actually fixed in the code, and the issues it chose not to fix along with a
  * reason. The prompt requires every input issue to land in exactly one list;
  * any title showing up in neither is silently dropped by the loop.
  */
case class FixOutcome(
    fixed: List[Title],
    ignored: List[IgnoredIssue]
) derives JsonData
