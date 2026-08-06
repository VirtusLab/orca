package orca.review

// Compiled under capture checking so `prepare`'s returned narrowing can be a
// PURE `->` arrow — enforced at compile time not to capture `InStage`. Tapir
// `derives`/macro-expanding types don't type-check under CC, so
// [[ReviewerInfo]]/[[ReviewerSelectionRequest]] live in a sibling non-CC file.
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{FlowContext, InStage}
import orca.events.OrcaEvent
import orca.agents.Agent
import orca.plan.Title

import scala.util.matching.Regex

/** Picks which reviewers run on each iteration of [[reviewAndFixLoop]].
  *
  * Two-phase: [[prepare]] is called ONCE at loop start with the loop-constant
  * context (the roster as opaque [[RosterEntry]] handles, task title, changed
  * files); any gated effect (e.g. [[ReviewerSelector.agentDriven]]'s picker LLM
  * call) happens there, inside the loop's stage. It returns the pure
  * per-iteration narrowing: given the review history (most recent batch first),
  * which reviewers run this round.
  *
  * A selector can only ever return a subset/permutation of the [[RosterEntry]]
  * handles it was handed (the ctor is `private[review]`), so the loop needs no
  * runtime roster-membership defence.
  *
  * The returned arrow is `->`, which rules out capturing [[orca.InStage]]: a
  * round cannot drive an LLM or spend tokens. Put every gated effect in
  * [[prepare]]; the arrow narrows over `history` and may emit a `Step` saying
  * what it decided, nothing more.
  */
trait ReviewerSelector:
  def prepare(
      all: List[RosterEntry[?]],
      taskTitle: Title,
      changedFiles: List[String]
  )(using FlowContext, InStage): List[ReviewBatch] -> List[RosterEntry[?]]

object ReviewerSelector:

  /** [[reviewAndFixLoop]]'s shipped default: [[agentDriven]] picks the set once
    * at loop start, and [[narrowingAcrossRounds]] then re-runs, each later
    * round, only the reviewers that reported something in the previous round.
    *
    * Two ways back to more coverage: [[allEveryRound]] runs the whole roster
    * every round, and the parameterless [[agentDriven]] — no parentheses —
    * picks once and replays that pick every round. Either costs a turn per
    * reviewer per round; the reason narrowing is the default is that a reviewer
    * which found nothing usually finds nothing again, and it's the reviewer
    * that reported which has to check the fix it triggered.
    */
  val default: ReviewerSelector = narrowingAcrossRounds(agentDriven)

  /** Costlier but thorough: every reviewer runs every iteration, regardless of
    * whether it's been quiet so far. Pick this when regression coverage matters
    * more than tokens.
    */
  val allEveryRound: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] -> List[RosterEntry[?]] =
      _ => all

  /** Asks a picker LLM which reviewers are worth running for a given task. The
    * parameterless form — `reviewAndFixLoop`'s default — resolves the picker at
    * loop start as [[orca.FlowContext.reviewAgent]]`.cheap`; the overload below
    * takes the picker (and optionally retuned prompts/descriptions) explicitly.
    * The selection is computed once, at loop start — task context doesn't
    * change mid-loop — and the returned per-round function replays it, ignoring
    * history.
    *
    * The picker sees each reviewer as a `(name, description)` pair.
    * `descriptions` defaults to [[ReviewerPrompts.descriptionsBySlug]]; supply
    * a custom map (keyed by bare slug) when overriding the default set. If the
    * picker would see all-empty descriptions, a one-time `Step` warning fires.
    *
    * The picker is handed the task title, the changed file names and those
    * descriptions, and runs under [[orca.agents.ToolSet.ReadOnly]] in the
    * flow's work dir. Whether a shell is there varies by backend — codex's
    * read-only sandbox runs commands, while claude, opencode and pi have no
    * shell tool at all — so the default brief tells it to open the changed
    * files rather than judge by their paths, to use `git diff HEAD` only if the
    * shell happens to be there, and to include a reviewer whenever it is
    * unsure.
    *
    * `filePatterns` is a code-side pre-filter applied before the LLM call:
    * reviewers whose pattern doesn't match any of `changedFiles` are dropped,
    * so the picker can't pick them. The default
    * ([[ReviewerPrompts.filePatternsBySlug]]) constrains only reviewers that
    * declared a `files:` frontmatter entry. See [[eligibleForPicker]] for what
    * an empty `changedFiles` means here.
    *
    * Pick a cheap model (e.g. `claude.haiku`) — the decision is small, though
    * the agent does open a few files to make it. Override `instructions` to
    * retune the selection brief.
    */
  def agentDriven: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using
        ctx: FlowContext,
        ev: InStage
    ): List[ReviewBatch] -> List[RosterEntry[?]] =
      agentDriven(ctx.reviewAgent.cheap).prepare(all, taskTitle, changedFiles)

  /** See the parameterless [[agentDriven]] above for the full description. Wrap
    * the result in [[narrowingAcrossRounds]] to also narrow across rounds, as
    * the loop's [[default]] does; on its own the pick is replayed every round.
    */
  def agentDriven(
      agent: Agent[?],
      instructions: String = ReviewLoopPrompts.SelectReviewers,
      descriptions: Map[String, String] = ReviewerPrompts.descriptionsBySlug,
      filePatterns: Map[String, Regex] = ReviewerPrompts.filePatternsBySlug
  ): ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using
        ctx: FlowContext,
        ev: InStage
    ): List[ReviewBatch] -> List[RosterEntry[?]] =
      val eligible = eligibleForPicker(all, filePatterns, changedFiles)
      val infos = eligible.map: r =>
        ReviewerInfo(
          name = r.name,
          description = descriptions.getOrElse(r.name, "")
        )
      if eligible.nonEmpty && infos.forall(_.description.isEmpty) then
        ctx.emit(
          OrcaEvent.Step(
            "reviewer selection: no descriptions matched the supplied " +
              "reviewers (custom reviewers without matching description " +
              "keys?). The picker will see names only."
          )
        )
      val names =
        if eligible.isEmpty then Nil
        else
          // Read-only: the picker decides which reviewers to run and must not
          // edit files during selection (reading context is fine).
          agent.withReadOnly
            .resultAs[SelectedReviewers]
            .autonomous
            .run(
              ReviewerSelectionRequest(
                taskTitle = taskTitle,
                changedFiles = changedFiles,
                availableReviewers = infos,
                instructions = instructions
              ),
              emitPrompt = false
            )
            .names
      // Post-filter against `eligible`, not `all`, so a picker that hallucinates
      // a name pre-filtered out can't resurrect it.
      val selected = SelectedReviewers(names).pick(eligible)
      // Safety floor: the picker narrows the set, it can't skip review. If it
      // picks nothing while reviewers are eligible, fall back to all eligible
      // so a real change is never silently unreviewed.
      val active =
        if selected.isEmpty && eligible.nonEmpty then
          ctx.emit(
            OrcaEvent.Step(
              s"reviewer selection: picker returned no usable names; " +
                s"falling back to all ${eligible.size} eligible reviewer(s)"
            )
          )
          eligible
        else selected
      _ => active

  /** Wraps `base` so its pick narrows as the fix loop iterates: the first round
    * runs whatever `base` selects, and every later round keeps only those of
    * `base`'s reviewers that reported an issue in the previous round. It
    * filters `base`'s per-round result and nothing else, so a reviewer `base`
    * excluded is never resurrected.
    *
    * Narrowing never empties a non-empty set: a round with no reviewer at all
    * would let the fixer keep editing unreviewed, since a lint gate keeps the
    * loop going through reviewer silence. When narrowing would leave none,
    * `base`'s pick runs again and a `Step` says so.
    */
  def narrowingAcrossRounds(base: ReviewerSelector): ReviewerSelector =
    new ReviewerSelector:
      def prepare(
          all: List[RosterEntry[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using
          ctx: FlowContext,
          ev: InStage
      ): List[ReviewBatch] -> List[RosterEntry[?]] =
        val basePick = base.prepare(all, taskTitle, changedFiles)
        history =>
          val active = basePick(history)
          history.headOption match
            case None => active
            case Some(previous) =>
              val reported = previous.reviewersWithIssues
              val narrowed = active.filter(e => reported.exists(_ eq e))
              // The `active.isEmpty` arm returns the same empty list the
              // fallback would; it is there to keep a base selector that picked
              // nobody from being announced as "re-running all 0 reviewer(s)".
              if narrowed.nonEmpty || active.isEmpty then narrowed
              else
                ctx.emit(
                  OrcaEvent.Step(
                    s"reviewer selection: nothing was reported last round; " +
                      s"re-running all ${active.size} selected reviewer(s) " +
                      s"rather than reviewing nothing"
                  )
                )
                active

  /** The reviewers [[agentDriven]] offers its picker: every reviewer with no
    * `files:` pattern, plus those whose pattern matches a changed file.
    *
    * An empty `changedFiles` is read as "unknown", not "nothing changed": a
    * caller-pinned `initialDiff` can miss files its diff text doesn't name.
    * Every reviewer stays eligible then, and a `Step` names the file-gated ones
    * that were kept: dropping one here removes it from the whole review and
    * nothing downstream can put it back.
    */
  private def eligibleForPicker(
      all: List[RosterEntry[?]],
      filePatterns: Map[String, Regex],
      changedFiles: List[String]
  )(using ctx: FlowContext): List[RosterEntry[?]] =
    if changedFiles.isEmpty then
      val gated = all.map(_.name).filter(filePatterns.contains)
      if gated.nonEmpty then
        ctx.emit(
          OrcaEvent.Step(
            s"reviewer selection: no changed files to match against; keeping " +
              s"${gated.size} file-gated reviewer(s) eligible " +
              s"(${gated.mkString(", ")})"
          )
        )
      all
    else
      all.filter: e =>
        filePatterns
          .get(e.name)
          .forall(rx => changedFiles.exists(rx.findFirstIn(_).isDefined))
