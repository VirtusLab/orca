package orca.review

import orca.llm.{Announce, JsonData, LlmTool, given}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}
import orca.Title
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

/** A single review finding. `title` is the one-line user-facing label (rendered
  * in the event log under `▶`); `description` is the longer form fed back to
  * the fixing agent and includes whatever context the reviewer gave. The split
  * mirrors `Plan.Task`'s title/description pair so flow scripts that handle
  * issues and tasks can use the same field names.
  */
case class ReviewIssue(
    severity: Severity,
    confidence: Double,
    title: Title,
    description: String,
    file: Option[String],
    line: Option[Int],
    suggestion: Option[String]
) derives JsonData

case class ReviewResult(
    issues: List[ReviewIssue]
) derives JsonData

object ReviewResult:
  val empty: ReviewResult = ReviewResult(Nil)

  /** Silent — `reviewAndFixLoop` emits per-reviewer Steps with the reviewer's
    * name; this would compete.
    */
  given Announce[ReviewResult] = Announce.from(_ => "")

case class IgnoredIssue(title: Title, reason: String) derives JsonData

case class IgnoredIssues(issues: List[IgnoredIssue]) derives JsonData:
  def ++(other: IgnoredIssues): IgnoredIssues = IgnoredIssues(
    issues ++ other.issues
  )
  def nonEmpty: Boolean = issues.nonEmpty
  def format: String =
    issues.map(i => s"- ${i.title}: ${i.reason}").mkString("\n")

object IgnoredIssues:
  /** Silent — the fix loop already announces its outcome per iteration. */
  given Announce[IgnoredIssues] = Announce.from(_ => "")

case class ReviewContext(summary: String, filesChanged: List[String])
    derives JsonData

case class SelectedReviewers(names: List[String]) derives JsonData:
  def pick(all: List[LlmTool[?]]): List[LlmTool[?]] =
    all.filter(r => names.contains(r.name))
