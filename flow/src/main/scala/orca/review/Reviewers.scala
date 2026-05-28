package orca.review

import orca.llm.{BackendTag, LlmTool}
import orca.util.PromptResource

import scala.util.matching.Regex

/** A reviewer agent definition: a short slug name, a description suitable for
  * LLM-driven selection ([[ReviewerSelector.llmDriven]]), and the system prompt
  * that personalises the underlying LLM tool. `filePattern`, when set,
  * restricts the reviewer to changes that touch at least one matching file —
  * the selector drops the reviewer before the picker LLM sees it.
  */
private[review] case class Reviewer(
    name: String,
    description: String,
    systemPrompt: String,
    filePattern: Option[Regex] = None
)

/** Canonical reviewer definitions the library ships with. Each entry reads from
  * a `.md` resource under `src/main/resources/orca/review/prompts/reviewers/`
  * with YAML-ish frontmatter:
  *
  *   - `description:` — short purpose blurb, used by the LLM-driven selector
  *     (required).
  *   - `files:` — substring-matched regex; the reviewer is only offered to the
  *     picker when at least one changed file matches (optional).
  */
private[review] object ReviewerPrompts:

  private def load(slug: String): Reviewer =
    val parsed = PromptResource.loadWithMetadata(
      s"/orca/review/prompts/reviewers/$slug.md"
    )
    val description = parsed.metadata.getOrElse(
      "description",
      throw new RuntimeException(
        s"reviewer '$slug' is missing 'description' in its frontmatter"
      )
    )
    val filePattern = parsed.metadata.get("files").map(_.r)
    Reviewer(slug, description, parsed.body, filePattern)

  val CodeFunctionality: Reviewer = load("code-functionality")
  val CodeStructure: Reviewer = load("code-structure")
  val Performance: Reviewer = load("performance")
  val Readability: Reviewer = load("readability")
  val ScalaFp: Reviewer = load("scala-fp")
  val Security: Reviewer = load("security")
  val Test: Reviewer = load("test")

  /** Every reviewer the library ships with. Order matches how `allReviewers`
    * configures them on the base tool.
    */
  val all: List[Reviewer] = List(
    CodeFunctionality,
    Test,
    Readability,
    CodeStructure,
    Performance,
    Security,
    ScalaFp
  )

  /** A small universally-applicable subset: correctness, test quality, clarity.
    * Useful as a starting point when the full set is overkill — e.g. a flow
    * that touches small diffs where performance/architecture concerns are
    * rarely actionable. Pair with [[ReviewerSelector.llmDriven]] (the default
    * in [[reviewAndFixLoop]]) to let the picker narrow further.
    */
  val minimal: List[Reviewer] = List(
    CodeFunctionality,
    Readability,
    Test
  )

  /** Descriptions keyed by the prefixed tool name a builder produces
    * (`reviewer: <slug>`). [[ReviewerSelector.llmDriven]] consults this by
    * default so the picker LLM gets each reviewer's purpose alongside its name.
    * Covers every shipped reviewer, regardless of which preset list was used to
    * build the actual tools.
    */
  val descriptionsByToolName: Map[String, String] =
    all.map(r => s"reviewer: ${r.name}" -> r.description).toMap

  /** File-filter regexes keyed by the prefixed tool name. The selector drops
    * reviewers whose pattern doesn't match any of the iteration's changed
    * files, before the picker LLM sees them. Only reviewers that declared a
    * `files:` frontmatter entry appear here.
    */
  val filePatternsByToolName: Map[String, Regex] =
    all.flatMap(r => r.filePattern.map(p => s"reviewer: ${r.name}" -> p)).toMap

/** Build LlmTools for every reviewer the library ships with. The picker in
  * [[ReviewerSelector.llmDriven]] (the default in [[reviewAndFixLoop]]) narrows
  * the active set per task, so passing the full list isn't wasteful.
  */
def allReviewers[B <: BackendTag](base: LlmTool[B]): List[LlmTool[B]] =
  buildReviewers(base, ReviewerPrompts.all)

/** Build LlmTools for the small universally-applicable subset
  * ([[ReviewerPrompts.minimal]] — correctness, test quality, clarity). Pick
  * this when the full set is overkill or the flow only touches small diffs.
  */
def minimalReviewers[B <: BackendTag](base: LlmTool[B]): List[LlmTool[B]] =
  buildReviewers(base, ReviewerPrompts.minimal)

/** Layer each reviewer's system prompt onto the base tool, prefix the name with
  * `reviewer: ` so the per-agent token breakdown groups every reviewer
  * dimension together (matching `lint`, which the review loop labels `reviewer:
  * lint`), and gate every reviewer to read-only access. A reviewer's job is to
  * *report* issues, not fix them; without `withReadOnly` the agent inherits the
  * base tool's permissions (typically `AutoApprove.All`) and could edit files
  * mid-review. Reads (Read/Glob/Grep on claude, `--sandbox read-only` on codex)
  * stay available so the agent can verify claims beyond the diff. The
  * non-reviewer driver agent keeps its default name (`main`) and write
  * permissions.
  */
private def buildReviewers[B <: BackendTag](
    base: LlmTool[B],
    reviewers: List[Reviewer]
): List[LlmTool[B]] =
  reviewers.map: r =>
    base
      .withSystemPrompt(r.systemPrompt)
      .withName(s"reviewer: ${r.name}")
      .withReadOnly
