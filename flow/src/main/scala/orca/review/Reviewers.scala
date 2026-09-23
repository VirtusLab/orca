package orca.review

import orca.{FlowContext, OrcaFlowException}
import orca.agents.{BackendTag, Agent}
import orca.util.{ParsedPrompt, PromptResource}

import ox.either
import ox.either.ok

import scala.util.matching.Regex

/** A reviewer agent definition: a slug name, a description suitable for
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
    name: ReviewerSlug,
    description: String,
    systemPrompt: String,
    filePattern: Option[Regex] = None
):
  // The picker's whole decision rests on the description, so a blank one is
  // rejected where the reviewer is built rather than noticed at selection time.
  // The same abort type discovery raises, so both sites report a broken
  // reviewer the same way.
  if description.isBlank then
    throw new OrcaFlowException(
      s"reviewer '$name' has an empty description — the reviewer-picker " +
        "decides from it; give the reviewer a one-line purpose blurb"
    )

  /** Whether this reviewer applies to a change set. An empty `changedFiles` is
    * "unknown", not "nothing changed" — a pinned diff can miss files its text
    * doesn't name — so every reviewer applies then.
    */
  def appliesTo(changedFiles: List[String]): Boolean =
    changedFiles.isEmpty ||
      filePattern.forall(rx => changedFiles.exists(rx.findFirstIn(_).isDefined))

/** A reviewer ready to run: its [[Reviewer]] definition and the agent
  * [[buildReviewers]] built from it. [[buildReviewers]] is the only production
  * site that mints one, and it is what keeps the pair consistent: the agent
  * carries `definition.name`, `definition.systemPrompt` and the read-only gate.
  * The loop reads the display name off `definition` and runs the turn on
  * `agent`.
  *
  * A flow wanting different base agents per reviewer concatenates calls:
  * `buildReviewers(strong, List(security)) ++ buildReviewers(cheap, rest)`.
  */
final case class ReviewerAgent[B <: BackendTag] private[review] (
    definition: Reviewer,
    agent: Agent[B]
)

/** Why one reviewer prompt file could not be turned into a [[Reviewer]].
  * `source` names the file, so the message says which one to fix.
  */
private[review] enum ReviewerPromptFailure:
  case NoFrontmatter(slug: ReviewerSlug, source: String)
  case MalformedFrontmatter(slug: ReviewerSlug, source: String)
  case Symlinked(source: String)
  case Directory(source: String)
  case DuplicateSlug(slug: ReviewerSlug, dir: String, files: List[String])
  case Unreadable(source: String, reason: String)
  case MissingDescription(slug: ReviewerSlug, source: String)
  case BlockScalar(slug: ReviewerSlug, source: String, key: String)
  case WrappedValue(slug: ReviewerSlug, source: String, key: String)
  case MissingBody(slug: ReviewerSlug, source: String)
  case InvalidFilePattern(
      slug: ReviewerSlug,
      source: String,
      pattern: String,
      reason: String
  )

private[review] object ReviewerPromptFailure:
  extension (failure: ReviewerPromptFailure)
    /** What the author is told, naming the file and what to do about it. */
    def message: String = failure match
      case NoFrontmatter(slug, source) =>
        s"reviewer '$slug' ($source) has no frontmatter block — add one at " +
          "the top: '---', a 'description:' line, then '---'"
      case Symlinked(source) =>
        s"$source is a symlink — refusing to read a reviewer prompt through " +
          "it; copy the file into the directory instead of linking it"
      case Directory(source) =>
        s"$source is a directory, not a reviewer prompt — a reviewer is a " +
          "single '.md' file; move it aside or rename it so it does not end " +
          "in '.md'"
      case DuplicateSlug(slug, dir, files) =>
        s"reviewer '$slug' is claimed by more than one file in $dir " +
          s"(${files.mkString(", ")}) — reviewer names are compared " +
          "case-insensitively; keep one"
      case Unreadable(source, reason) =>
        s"$source cannot be read: $reason"
      case MalformedFrontmatter(slug, source) =>
        s"reviewer '$slug' ($source) has a frontmatter block the parser " +
          "could not read — it must open with '---' on the first line, close " +
          "with '---', and hold `key: value` lines"
      case MissingDescription(slug, source) =>
        s"reviewer '$slug' ($source) has no 'description:' in its " +
          "frontmatter — add one saying what the reviewer checks"
      case BlockScalar(slug, source, key) =>
        s"reviewer '$slug' ($source) writes '$key:' as a YAML block scalar, " +
          "which the frontmatter parser does not read — put the whole value " +
          s"on the '$key:' line"
      case WrappedValue(slug, source, key) =>
        s"reviewer '$slug' ($source) wraps its '$key:' value onto the next " +
          "line, which the frontmatter parser does not read — put the whole " +
          s"value on the '$key:' line"
      case MissingBody(slug, source) =>
        s"reviewer '$slug' ($source) has no body below the closing '---' — " +
          "the body is the reviewer's system prompt"
      case InvalidFilePattern(slug, source, pattern, reason) =>
        s"reviewer '$slug' ($source) has an invalid 'files:' regex " +
          s"'$pattern': $reason"

/** Build a [[Reviewer]] from one parsed reviewer prompt file. `slug` is the
  * reviewer's identity — a `name:` key in the frontmatter is ignored.
  * `description:` and a non-empty body are required, and `files:`, when
  * present, must be a valid regex. Both keys hold a one-line value; a YAML
  * block-scalar header (`>`, `|-`, …) or a value wrapped onto the next line is
  * refused, since the parser never reads the lines after the key's own. Other
  * keys are ignored, wrapped or not.
  *
  * The one conversion for both sources: the shipped prompts under
  * `src/main/resources` and the `.md` files [[ReviewerCatalog]] discovers. The
  * failure is a value because the two differ in what to do with it — a broken
  * shipped resource is a defect, a broken discovered file is the author's to
  * correct, and discovery reports every bad file at once.
  */
private[review] def reviewerFrom(
    slug: ReviewerSlug,
    parsed: ParsedPrompt,
    source: String
): Either[ReviewerPromptFailure, Reviewer] =
  either:
    // Only README/`_`-prefixed names are exempted earlier, by filename — so a
    // file that never opened a block reaches here and is told to add one,
    // rather than being told its block is unreadable.
    if !parsed.hasFrontmatter then
      Left(ReviewerPromptFailure.NoFrontmatter(slug, source)).ok()
    if parsed.metadata.isEmpty then
      Left(ReviewerPromptFailure.MalformedFrontmatter(slug, source)).ok()
    // A block scalar's lines wrap too, so it is checked first for the more
    // specific message; both precede the description check, so an empty
    // `description:` line with its value below is not reported as missing.
    OneLineKeys
      .find(key => parsed.metadata.get(key).exists(isBlockScalarHeader))
      .map(ReviewerPromptFailure.BlockScalar(slug, source, _))
      .toLeft(())
      .ok()
    OneLineKeys
      .find(parsed.wrappedKeys.contains)
      .map(ReviewerPromptFailure.WrappedValue(slug, source, _))
      .toLeft(())
      .ok()
    val description = parsed.metadata
      .get("description")
      .filterNot(_.isBlank)
      .toRight(ReviewerPromptFailure.MissingDescription(slug, source))
      .ok()
    // An instruction-less reviewer still costs a turn and still reports
    // nothing, which reads exactly like a clean review.
    if parsed.body.isBlank then
      Left(ReviewerPromptFailure.MissingBody(slug, source)).ok()
    val filePattern = parsed.metadata.get("files").filter(_.nonEmpty) match
      case None          => None
      case Some(pattern) =>
        // `Regex` signals only by throwing; this is the bridge to a value.
        val compiled =
          try Right(pattern.r)
          catch
            case e: java.util.regex.PatternSyntaxException =>
              Left(
                ReviewerPromptFailure
                  .InvalidFilePattern(slug, source, pattern, e.getDescription)
              )
        Some(compiled.ok())
    Reviewer(slug, description, parsed.body, filePattern)

/** The frontmatter keys a reviewer reads; each holds a one-line value. */
private val OneLineKeys = List("description", "files")

/** `>` or `|`, optionally followed by chomping and indentation indicators and a
  * comment.
  */
private def isBlockScalarHeader(value: String): Boolean =
  value.matches("""[>|][-+0-9]*(\s+#.*)?""")

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

  // A shipped prompt that doesn't parse is a packaging defect, not something a
  // user can fix, so it fails the object's initialization rather than a run.
  private def load(slug: String): Reviewer =
    val path = s"/orca/review/prompts/reviewers/$slug.md"
    reviewerFrom(
      ReviewerSlug(slug),
      PromptResource.loadWithMetadata(path),
      path
    )
      .fold(f => throw new RuntimeException(f.message), identity)

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

/** Build a [[ReviewerAgent]] for every reviewer in the run's
  * [[orca.FlowContext.reviewerCatalog]]: the shipped set, plus whatever
  * `.orca/reviewers/` and the user-global reviewer directory add, with a
  * same-named file replacing the shipped reviewer it names. The default picker
  * ([[ReviewerSelector.agentDriven]]) narrows the active set per task, so
  * passing the full list isn't wasteful.
  */
def allReviewers[B <: BackendTag](base: Agent[B])(using
    ctx: FlowContext
): List[ReviewerAgent[B]] =
  buildReviewers(base, ctx.reviewerCatalog.all)

/** Build [[ReviewerAgent]]s for the small universally-applicable subset
  * (correctness, test quality, clarity). Pick this when the full set is
  * overkill or the flow only touches small diffs.
  *
  * A reviewer discovered under a slug nothing ships is in this list too — a
  * project that ships one means it for small diffs as well. One that shadows a
  * shipped reviewer replaces it only where that reviewer already appears, so a
  * file named after a shipped reviewer outside this subset (`security`, say)
  * changes [[allReviewers]] and leaves this list alone.
  */
def minimalReviewers[B <: BackendTag](base: Agent[B])(using
    ctx: FlowContext
): List[ReviewerAgent[B]] =
  buildReviewers(base, ctx.reviewerCatalog.minimal)

/** Pair each reviewer definition with the agent that runs it: its system prompt
  * layered onto the base tool, named with the bare reviewer slug and gated to
  * read-only access. A reviewer's job is to *report* findings, not fix them;
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
        .withName(r.name.value)
        .withReadOnly
    )
