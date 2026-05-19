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

  /** The full default set in the order `defaultReviewers` configures them. */
  val all: List[Reviewer] = List(
    Performance,
    Readability,
    Test,
    CodeFunctionality,
    Abstraction,
    BackendArchitect,
    ScalaFp
  )

  /** Descriptions keyed by the prefixed tool name `defaultReviewers` produces
    * (`reviewer: <slug>`). [[ReviewerSelector.llmDriven]] consults this by
    * default so the picker LLM gets each reviewer's purpose alongside its name.
    */
  val descriptionsByToolName: Map[String, String] =
    all.map(r => s"reviewer: ${r.name}" -> r.description).toMap

/** Pre-configured reviewer agents built atop the supplied base tool. Each
  * reviewer has its own `name` and system prompt; callers pass them (or a
  * subset via `SelectedReviewers.pick`) to `reviewAndFixLoop`.
  */
def defaultReviewers[B <: BackendTag](base: LlmTool[B]): List[LlmTool[B]] =
  // Names are prefixed with `reviewer: ` so the per-agent token breakdown
  // groups every reviewer dimension together (matching `lint`, which the
  // review loop labels `reviewer: lint`). The non-reviewer driver agent
  // keeps its default name (`main`).
  ReviewerPrompts.all.map: r =>
    base.withSystemPrompt(r.systemPrompt).withName(s"reviewer: ${r.name}")
