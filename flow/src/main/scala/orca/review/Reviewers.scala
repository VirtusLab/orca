package orca.review

import orca.agents.{BackendTag, Agent}
import orca.util.PromptResource

import scala.util.matching.Regex

/** A reviewer agent definition: a short slug name, a description suitable for
  * LLM-driven selection ([[ReviewerSelector.agentDriven]]), and the system
  * prompt that personalises the underlying LLM tool. `filePattern`, when set,
  * restricts the reviewer to changes that touch at least one matching file —
  * the selector drops the reviewer before the picker LLM sees it.
  *
  * Public so a flow can define its own reviewers alongside the shipped
  * [[ReviewerPrompts]] set: build a `List[Reviewer]`, turn it into
  * [[ReviewerAgent]]s with [[buildReviewers]], and hand those to
  * [[reviewAndFixLoop]]. A custom reviewer is described and gated by its own
  * fields, like a shipped one.
  */
case class Reviewer(
    name: String,
    description: String,
    systemPrompt: String,
    filePattern: Option[Regex] = None
):
  // The picker's whole decision rests on the description, so a blank one is
  // rejected where the reviewer is built rather than noticed at selection time.
  require(
    description.nonEmpty,
    s"reviewer '$name' must have a non-empty description"
  )

  /** Whether this reviewer applies to a change set. An empty `changedFiles` is
    * "unknown", not "nothing changed" — a pinned diff can miss files its text
    * doesn't name — so every reviewer applies then.
    */
  def appliesTo(changedFiles: List[String]): Boolean =
    changedFiles.isEmpty ||
      filePattern.forall(rx => changedFiles.exists(rx.findFirstIn(_).isDefined))

/** A reviewer ready to run: its [[Reviewer]] definition and the agent
  * [[buildReviewers]] built from it. Only [[buildReviewers]] can mint one, so
  * `agent` always carries `definition.name`, `definition.systemPrompt` and the
  * read-only gate — the loop reads the display name off `definition` and runs
  * the turn on `agent`, and the two cannot disagree.
  *
  * A flow wanting different base agents per reviewer concatenates calls:
  * `buildReviewers(strong, List(security)) ++ buildReviewers(cheap, rest)`.
  */
final case class ReviewerAgent[B <: BackendTag] private[review] (
    definition: Reviewer,
    agent: Agent[B]
)

/** Canonical reviewer definitions the library ships with. Each entry reads from
  * a `.md` resource under `src/main/resources/orca/review/prompts/reviewers/`
  * with YAML-ish frontmatter:
  *
  *   - `description:` — short purpose blurb, used by the LLM-driven selector
  *     (required).
  *   - `files:` — substring-matched regex; the reviewer is only offered to the
  *     picker when at least one changed file matches (optional).
  *
  * Public as the customization surface: reference individual reviewers
  * ([[CodeFunctionality]], [[Security]], …) or the preset lists ([[all]],
  * [[minimal]]) when composing your own reviewer list. Pair with
  * [[buildReviewers]].
  */
object ReviewerPrompts:

  /** Role tag that gathers the whole review side of a run into one by-role
    * subtotal: every reviewer's turn, lint, the picker, and the run's review
    * role agent.
    *
    * A reviewer gets it via `Agent.withRole` at the loop's emission edge only,
    * never baked into the agent's `name`/identity — the roster, session map,
    * picker, and outcomes all keep using the bare slug.
    */
  val Role: String = "reviewer"

  private def load(slug: String): Reviewer =
    val parsed = PromptResource.loadWithMetadata(
      s"/orca/review/prompts/reviewers/$slug.md"
    )
    val description = parsed.metadata
      .get("description")
      .filter(_.nonEmpty)
      .getOrElse(
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
  val Simplicity: Reviewer = load("simplicity")
  val Test: Reviewer = load("test")

  /** Every reviewer the library ships with. Order matches how `allReviewers`
    * configures them on the base tool.
    */
  val all: List[Reviewer] = List(
    CodeFunctionality,
    Test,
    Readability,
    CodeStructure,
    Simplicity,
    Performance,
    Security,
    ScalaFp
  )

  /** A small universally-applicable subset: correctness, test quality, clarity.
    * Useful when the full set is overkill — e.g. a flow that touches small
    * diffs where performance/architecture concerns are rarely actionable.
    */
  val minimal: List[Reviewer] = List(
    CodeFunctionality,
    Readability,
    Test
  )

/** Build a [[ReviewerAgent]] for every reviewer the library ships with. The
  * default picker ([[ReviewerSelector.agentDriven]]) narrows the active set per
  * task, so passing the full list isn't wasteful.
  */
def allReviewers[B <: BackendTag](base: Agent[B]): List[ReviewerAgent[B]] =
  buildReviewers(base, ReviewerPrompts.all)

/** Build [[ReviewerAgent]]s for the small universally-applicable subset
  * ([[ReviewerPrompts.minimal]] — correctness, test quality, clarity). Pick
  * this when the full set is overkill or the flow only touches small diffs.
  */
def minimalReviewers[B <: BackendTag](
    base: Agent[B]
): List[ReviewerAgent[B]] =
  buildReviewers(base, ReviewerPrompts.minimal)

/** Pair each reviewer definition with the agent that runs it: its system prompt
  * layered onto the base tool, named with the bare reviewer slug and gated to
  * read-only access. A reviewer's job is to *report* issues, not fix them;
  * without `withReadOnly` the agent inherits the base tool's permissions
  * (typically `AutoApprove.All`) and could edit files mid-review. Reads stay
  * available so the agent can verify claims beyond the diff.
  *
  * How strongly that gate holds is per backend and per turn — AGENTS.md's
  * enforcement table is the answer. The run says so when it isn't mechanical
  * (`EnforcementNotice`), and the read-only rule is in every such turn's prompt
  * either way.
  *
  * Public so a flow can build reviewers from a custom [[Reviewer]] list rather
  * than being limited to the [[allReviewers]] / [[minimalReviewers]] presets.
  */
def buildReviewers[B <: BackendTag](
    base: Agent[B],
    reviewers: List[Reviewer]
): List[ReviewerAgent[B]] =
  reviewers.map: r =>
    ReviewerAgent(
      r,
      base
        .withSystemPrompt(r.systemPrompt)
        .withName(r.name)
        .withReadOnly
    )
