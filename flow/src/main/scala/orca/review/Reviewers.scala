package orca.review

import orca.llm.{BackendTag, LlmTool}
import orca.util.PromptResource

/** A reviewer agent definition: a short slug name, a description suitable for
  * LLM-driven selection ([[ReviewerSelector.llmDriven]]), and the system prompt
  * that personalises the underlying LLM tool.
  */
case class Reviewer(name: String, description: String, systemPrompt: String)

/** Canonical reviewer definitions the library ships with. Each entry reads from
  * a `.md` resource under `src/main/resources/orca/review/prompts/reviewers/`
  * with YAML-ish frontmatter (`description:` is parsed; the body becomes the
  * system prompt).
  */
object ReviewerPrompts:

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
    Reviewer(slug, description, parsed.body)

  val Abstraction: Reviewer = load("abstraction")
  val BackendArchitect: Reviewer = load("backend-architect")
  val CodeFunctionality: Reviewer = load("code-functionality")
  val Performance: Reviewer = load("performance")
  val Readability: Reviewer = load("readability")
  val ScalaFp: Reviewer = load("scala-fp")
  val Test: Reviewer = load("test")

  /** Every reviewer the library ships with. Order matches how `allReviewers`
    * configures them on the base tool.
    */
  val all: List[Reviewer] = List(
    Performance,
    Readability,
    Test,
    CodeFunctionality,
    Abstraction,
    BackendArchitect,
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

/** Layer each reviewer's system prompt onto the base tool and prefix the name
  * with `reviewer: ` so the per-agent token breakdown groups every reviewer
  * dimension together (matching `lint`, which the review loop labels `reviewer:
  * lint`). The non-reviewer driver agent keeps its default name (`main`).
  */
private def buildReviewers[B <: BackendTag](
    base: LlmTool[B],
    reviewers: List[Reviewer]
): List[LlmTool[B]] =
  reviewers.map: r =>
    base.withSystemPrompt(r.systemPrompt).withName(s"reviewer: ${r.name}")
