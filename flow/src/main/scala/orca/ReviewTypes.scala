package orca

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}
import sttp.tapir.Schema

enum Severity:
  case Critical
  case Warning
  case Info

object Severity:
  // Severity keeps its own Schema + JsonValueCodec so the enum renders as a
  // plain JSON string ("Critical", "Warning", "Info") in prompts and output
  // rather than the default object-with-discriminator shape.
  given Schema[Severity] = Schema.derivedEnumeration.defaultStringBased
  given JsonValueCodec[Severity] =
    JsonCodecMaker.make(CodecMakerConfig.withDiscriminatorFieldName(None))

/** A single review finding. `shortSummary` is the one-line user-facing
  * label (rendered in the event log under `▶`); `description` is the
  * longer form fed back to the fixing agent and includes whatever
  * context the reviewer gave. The split mirrors `Plan.Task`'s
  * shortSummary/description pair so flow scripts that handle issues
  * and tasks can use the same field names.
  */
case class ReviewIssue(
    severity: Severity,
    confidence: Double,
    shortSummary: String,
    description: String,
    file: Option[String],
    line: Option[Int],
    suggestion: Option[String]
) derives JsonData

case class ReviewResult(
    issues: List[ReviewIssue],
    summary: String
) derives JsonData

object ReviewResult:
  val empty: ReviewResult = ReviewResult(Nil, "")

  /** ReviewResult intentionally has no auto-announce. Per-reviewer
    * Steps are emitted by [[reviewAndFixLoop]]'s evaluate closure
    * with the reviewer's name in the line — provenance the
    * type-level `Announce[ReviewResult]` can't see, since the
    * reviewer name lives on the `LlmTool` and not on the result.
    * Returning `""` lets the terminal listener treat the per-call
    * `StructuredResult` as silent so it doesn't compete with the
    * named per-reviewer line.
    */
  given Announce[ReviewResult] = Announce.from(_ => "")

case class IgnoredIssue(issue: ReviewIssue, reason: String) derives JsonData

case class IgnoredIssues(issues: List[IgnoredIssue]) derives JsonData:
  def ++(other: IgnoredIssues): IgnoredIssues = IgnoredIssues(
    issues ++ other.issues
  )
  def nonEmpty: Boolean = issues.nonEmpty
  def format: String =
    issues
      .map(i => s"- [${i.issue.severity}] ${i.issue.shortSummary}: ${i.reason}")
      .mkString("\n")

object IgnoredIssues:
  /** Silent like `ReviewResult` — the fix loop already names what
    * happened via "Fixed review comments" / "Unable to fix" / "All
    * N marked as won't-fix" steps inside the iteration stage, so
    * an additional auto-announce would just duplicate.
    */
  given Announce[IgnoredIssues] = Announce.from(_ => "")

case class ReviewContext(summary: String, filesChanged: List[String])
    derives JsonData

case class SelectedReviewers(names: List[String]) derives JsonData:
  def pick(all: List[LlmTool[?]]): List[LlmTool[?]] =
    all.filter(r => names.contains(r.name))
