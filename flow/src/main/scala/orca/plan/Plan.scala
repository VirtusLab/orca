package orca.plan

import orca.{FlowContext, OrcaFlowException}
import orca.llm.{Announce, BackendTag, CanAskUser, JsonData, LlmTool, given}
import orca.events.OrcaEvent
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

/** The shared surface of a development plan, with or without a codebase brief.
  *
  * Implemented by [[Plan]] (bare) and [[PlanWithBrief]]. The brief is a
  * distinct type, not an `Option[String]` field, so it stays out of [[Plan]]'s
  * structured-output schema and the planner can't fabricate one. Persistence
  * and the task loop work against this trait, so the brief rides in the single
  * plan file and is cleaned up with it.
  */
sealed trait PlanLike:
  def epicId: String
  def description: String
  def tasks: List[Task]

  /** First task whose `completed` flag is false, in declaration order. `None`
    * means the plan is fully done.
    */
  def firstIncomplete: Option[Task] = tasks.find(!_.completed)

  /** Mark the task with the given `title` complete, leaving the others
    * untouched. Returns the same plan (same concrete type) if no task matches.
    */
  def markComplete(title: Title): PlanLike

  /** Prompt for `task`: its description, with the shared brief (when present)
    * prepended.
    */
  def taskPrompt(task: Task): String

object PlanLike:

  /** Sum-type codec for `PlanLike`. Both subtypes (`Plan`, `PlanWithBrief`)
    * `derives JsonData` without a discriminator —
    * `JsonCodecMaker.make[PlanLike]` produces an inconsistent codec where the
    * encoder writes fields directly (no `"type"` field) but the decoder
    * requires `"type"` as the first key. This hand-written codec uses
    * `{"type":"<TypeName>","value":{…}}` so encode and decode always agree on
    * the wire format.
    *
    * The decoder is key-order tolerant: it collects `type` and `value` from the
    * object regardless of order, ignoring unknown keys for
    * forward-compatibility.
    */
  given JsonData[PlanLike] =
    val planCodec: JsonValueCodec[Plan] =
      summon[JsonData[Plan]].codec
    val pwbCodec: JsonValueCodec[PlanWithBrief] =
      summon[JsonData[PlanWithBrief]].codec
    val configuredCodec = new ConfiguredJsonValueCodec[PlanLike]:
      def encodeValue(x: PlanLike, out: JsonWriter): Unit =
        out.writeObjectStart()
        out.writeKey("type")
        x match
          case p: Plan =>
            out.writeVal("Plan")
            out.writeKey("value")
            planCodec.encodeValue(p, out)
          case p: PlanWithBrief =>
            out.writeVal("PlanWithBrief")
            out.writeKey("value")
            pwbCodec.encodeValue(p, out)
        out.writeObjectEnd()
      def decodeValue(in: JsonReader, default: PlanLike): PlanLike =
        if !in.isNextToken('{') then in.decodeError("expected '{'")
        var typeName: String | Null = null
        // We cannot eagerly decode the value object if we haven't seen the
        // discriminator yet — capture raw bytes to replay when type is known.
        var valueBytes: Array[Byte] | Null = null
        var decoded: PlanLike | Null = null
        if !in.isNextToken('}') then
          in.rollbackToken()
          var continue = true
          while continue do
            in.readKeyAsString() match
              case "type" =>
                typeName = in.readString(null)
                // If we already buffered the value bytes, decode them now.
                if valueBytes != null then
                  decoded = typeName match
                    case "Plan" =>
                      com.github.plokhotnyuk.jsoniter_scala.core
                        .readFromArray(valueBytes)(planCodec)
                    case "PlanWithBrief" =>
                      com.github.plokhotnyuk.jsoniter_scala.core
                        .readFromArray(valueBytes)(pwbCodec)
                    case other =>
                      in.decodeError(s"unknown PlanLike type: $other")
              case "value" =>
                typeName match
                  case null =>
                    // `type` not yet seen — capture raw bytes to decode later
                    valueBytes = in.readRawValAsBytes()
                  case known =>
                    // `type` already seen — decode immediately
                    decoded = known match
                      case "Plan" =>
                        planCodec.decodeValue(in, planCodec.nullValue)
                      case "PlanWithBrief" =>
                        pwbCodec.decodeValue(in, pwbCodec.nullValue)
                      case other =>
                        in.decodeError(s"unknown PlanLike type: $other")
              case _ => in.skip() // forward-compat: ignore unknown keys
            continue = in.isNextToken(',')
          if !in.isCurrentToken('}') then
            in.rollbackToken()
            if !in.isNextToken('}') then in.decodeError("expected '}'")
        if typeName == null then
          in.decodeError("missing discriminator key 'type'")
        if decoded == null then in.decodeError("missing key 'value'")
        decoded
      def nullValue: PlanLike = planCodec.nullValue
    JsonData[PlanLike](Schema.derived[PlanLike], configuredCodec)

/** A development plan: an ordered list of [[Task]]s the agent will work
  * through, all on a single branch named by `epicId` (kebab-case, used directly
  * as the git branch name).
  *
  * ==Planning grid==
  *
  * The planning entry points form a `mode × operation` grid. Mode and operation
  * are orthogonal — every combination is valid:
  *
  *   - **mode** — [[autonomous]] (single agentic turn, read-only, no human) or
  *     [[interactive]] (a conversation the agent can drive via `ask_user`).
  *     Chosen by the nested object so it's visible at the call site, mirroring
  *     `claude.autonomous.run` / `claude.resultAs[O].interactive.run`.
  *   - **operation** — `from` (produce a [[Plan]] directly), `assessThenPlan`
  *     (skeptically assess first, returning a [[Verdict]] that either proceeds
  *     with a plan or rejects), or `triage` (classify a bug report into a
  *     [[Triage]] verdict).
  *
  * Every cell returns a [[Sessioned]] — the result plus the agent session that
  * produced it. From a `Sessioned[B, Plan]` the same session can be continued
  * read-only into [[Sessioned.reviewed]] (self-critique) and
  * [[Sessioned.briefed]] (codebase brief), or discarded for a fresh implementer
  * session.
  *
  * ==Persistence==
  *
  * [[recoverOrCreate]] + [[persistComplete]] + [[implementTaskLoop]] round-trip
  * a plan through a `## Task: …` markdown file (parsed/rendered by [[parse]] /
  * [[render]]) so a flow that crashes mid-loop resumes without re-planning.
  *
  * `derives JsonData` so the structured-output path works directly: the helper
  * methods consume Orca's auto-generated JSON schema; no caller-side
  * serialization is needed.
  */
case class Plan(
    epicId: String,
    description: String,
    tasks: List[Task]
) extends PlanLike derives JsonData:

  def markComplete(title: Title): Plan =
    copy(tasks = tasks.map(t => if t.title == title then t.markComplete else t))

  def taskPrompt(task: Task): String = task.description

/** A [[Plan]] paired with a codebase brief for the implementing agents.
  *
  * Generated by [[Sessioned.briefed]] and prepended to every task via
  * [[taskPrompt]]; persists as a trailing `## Brief` section. `derives
  * JsonData` so [[Sessioned.reviewed]] can re-emit plan and brief together.
  */
case class PlanWithBrief(plan: Plan, brief: String) extends PlanLike
    derives JsonData:
  def epicId: String = plan.epicId
  def description: String = plan.description
  def tasks: List[Task] = plan.tasks
  def markComplete(title: Title): PlanWithBrief =
    copy(plan = plan.markComplete(title))
  def taskPrompt(task: Task): String =
    s"$brief\n\n---\n\n${task.description}"

object Plan:

  /** Subdirectory under `workDir` where persistent plans live. Hidden so plain
    * `ls` doesn't show it; convention rather than configuration to keep
    * resume-after-crash predictable across flows.
    */
  val DefaultDir: os.SubPath = os.sub / ".orca"

  /** Default path for a persistent plan. `<workDir>/.orca/plan-<hash>.md` where
    * `<hash>` is the first 12 hex chars of SHA-256(userPrompt). Two unrelated
    * prompts in the same repo get different files; rerunning the same prompt
    * resumes the same plan.
    */
  def defaultPath(userPrompt: String, workDir: os.Path = os.pwd): os.Path =
    workDir / DefaultDir / s"plan-${hashUserPrompt(userPrompt)}.md"

  /** First 6 bytes of SHA-256(userPrompt) rendered as 12 hex chars. Visible for
    * testing; flow scripts should go through [[defaultPath]].
    */
  private[plan] def hashUserPrompt(userPrompt: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(userPrompt.getBytes("UTF-8"))
    digest.iterator.take(6).map(b => f"${b & 0xff}%02x").mkString

  /** Autonomous planning — a single agentic turn, no human in the loop. The
    * agent runs `NetworkOnly` (`.withNetworkOnly`): it can verify claims via
    * Read/Grep and read-only network (issues/PRs/web) but can't edit during the
    * planning turn (see [[autonomousResult]] for the per-backend guarantee).
    * Sibling of [[interactive]]; the choice between the two is visible at the
    * call site (`Plan.autonomous.from(...)` vs `Plan.interactive.from(...)`),
    * mirroring `LlmTool`'s own `autonomous` / `interactive` split.
    *
    * Each operation returns a [[Sessioned]]: the read-only planning turn's
    * session is still resumable by a later writable call, so the caller can
    * continue it into implementation or discard it for a fresh session.
    *
    * No `CanAskUser` bound (unlike [[interactive]]): an autonomous turn never
    * calls `ask_user`, so it works with any backend.
    */
  object autonomous:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Sessioned[B, Plan] =
      autonomousResult[B, Plan, Plan](llm, userPrompt, instructions)(identity)

    /** Skeptically assess `userPrompt` (typically a bug/feature report) and
      * either proceed with a plan or reject with a [[Verdict.Rejection]] the
      * caller surfaces to whoever filed it.
      */
    def assessThenPlan[B <: BackendTag](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext): Sessioned[B, Verdict[Plan]] =
      autonomousResult[B, AssessedPlan, Verdict[Plan]](
        llm,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict (not-a-bug / untestable
      * / testable).
      */
    def triage[B <: BackendTag](
        report: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext): Sessioned[B, Triage] =
      autonomousResult[B, BugTriage, Triage](llm, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

    /** Persistence convenience: parse `file` if it exists (resume), else
      * generate via [[from]] and persist. Returns a bare [[PlanLike]] — unlike
      * the grid operations above — because the resume branch has no session to
      * expose. The implementer mints its own session.
      */
    def loadOrGenerate[B <: BackendTag](
        file: os.Path,
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): PlanLike =
      loadOrGenerateImpl(file, () => from(userPrompt, llm, instructions).value)

  /** Interactive planning — the LLM call opens a conversation the user can
    * drive (clarifying questions, refinements) before the agent produces the
    * result. Not read-only: claude's plan mode would disable the `ask_user` MCP
    * tool, so the agent runs with normal permissions; the prompt asks it not to
    * edit, and the user sees any violation.
    *
    * The `B: CanAskUser` constraint means these compile only with backends that
    * can host an `ask_user` tool — claude and codex (both via the shared
    * `AskUserMcpServer`) and pi (via Orca's temporary ask-user extension). A
    * future stdin-only backend would fail this at compile time. Use
    * [[autonomous]] when no mid-session questions are needed.
    *
    * Each operation returns a [[Sessioned]] so the conversation can carry into
    * implementation (e.g. a triage agent's exploration informs the fix).
    */
  object interactive:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag: CanAskUser](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Sessioned[B, Plan] =
      interactiveResult[B, Plan, Plan](llm, userPrompt, instructions)(identity)

    /** Skeptically assess `userPrompt`, but able to ask the reporter clarifying
      * questions mid-turn rather than only rejecting with a
      * [[Verdict.RejectionKind.Question]].
      */
    def assessThenPlan[B <: BackendTag: CanAskUser](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext): Sessioned[B, Verdict[Plan]] =
      interactiveResult[B, AssessedPlan, Verdict[Plan]](
        llm,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict, able to ask the
      * reporter clarifying questions before deciding.
      */
    def triage[B <: BackendTag: CanAskUser](
        report: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext): Sessioned[B, Triage] =
      interactiveResult[B, BugTriage, Triage](llm, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

    /** Persistence convenience: parse `file` if it exists (resume), else
      * generate via [[from]] and persist. Returns a bare [[PlanLike]] — see the
      * autonomous counterpart for why no session is exposed.
      */
    def loadOrGenerate[B <: BackendTag: CanAskUser](
        file: os.Path,
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): PlanLike =
      loadOrGenerateImpl(file, () => from(userPrompt, llm, instructions).value)

  /** Append the operation's instruction block to the caller's input. */
  private def withInstructions(input: String, instructions: String): String =
    s"$input\n\n$instructions"

  /** Surface a structured-contract violation (a `toVerdict` / `toTriage`
    * `Left`) as a flow failure. The decode succeeded but the field combination
    * was incoherent past the retry loop, so it's a system-level failure.
    */
  private def getOrFail[A](result: Either[String, A]): A =
    result.fold(msg => throw OrcaFlowException(msg), identity)

  /** Common load-or-generate body: read+log on resume, otherwise call
    * `generate` and persist its result. The two public variants differ only in
    * which LLM-call shape they pass for `generate` (interactive vs autonomous).
    */
  private def loadOrGenerateImpl(file: os.Path, generate: () => PlanLike)(using
      ctx: FlowContext
  ): PlanLike =
    if os.exists(file) then
      val parsed = parse(os.read(file))
      ctx.emit(
        OrcaEvent.Step(
          s"Reusing existing plan at $file (${parsed.tasks.size} task(s), ${parsed.tasks.count(_.completed)} already complete)"
        )
      )
      parsed
    else
      val plan = generate()
      os.write.over(file, render(plan), createFolders = true)
      plan

  /** Run one autonomous turn producing wire type `O`, convert it to the public
    * result `A`, and pair it with the session. Shared by every `autonomous.*`
    * operation (`from`, `assessThenPlan`, `triage`).
    *
    * Runs `NetworkOnly`: reads plus read-only network, so the planner can fetch
    * an issue/PR it was pointed at and verify external claims. Edits stay
    * blocked (hard on claude/gemini/opencode; prompt-only on pi/codex — the
    * planning prompts forbid edits). Reviewers and the post-planning
    * `reviewed`/`briefed` turns use plain `withReadOnly` instead — no network,
    * hard no-edit everywhere.
    */
  private def autonomousResult[B <: BackendTag, O: JsonData: Announce, A](
      llm: LlmTool[B],
      input: String,
      instructions: String
  )(convert: O => A)(using FlowContext): Sessioned[B, A] =
    val (sessionId, raw) = llm.withNetworkOnly
      .resultAs[O]
      .autonomous
      .run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  /** Interactive counterpart to [[autonomousResult]]. */
  private def interactiveResult[
      B <: BackendTag: CanAskUser,
      O: JsonData: Announce,
      A
  ](
      llm: LlmTool[B],
      input: String,
      instructions: String
  )(convert: O => A)(using FlowContext): Sessioned[B, A] =
    val (sessionId, raw) =
      llm.resultAs[O].interactive.run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  // == Post-planning steps on a produced plan ==
  //
  // `reviewed` / `briefed` resume the planning session read-only, reusing the
  // planner's exploration. Defined here (and in `PlanWithBrief`) so they're in
  // the implicit scope of `Sessioned[B, Plan]` — no extra import needed.

  extension [B <: BackendTag](sp: Sessioned[B, Plan])
    /** Resume the planning session for a critical self-review, returning the
      * improved plan paired with the (same) session.
      */
    def reviewed(
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Review
    )(using FlowContext): Sessioned[B, Plan] =
      val (sessionId, improved) = llm.withReadOnly
        .resultAs[Plan]
        .autonomous
        .run(s"$instructions\n\n${render(sp.value)}", session = sp.sessionId)
      Sessioned(sessionId, improved)

    /** Resume the planning session for a one-off codebase brief, attached as a
      * [[PlanWithBrief]]. Brief last: a later bare-plan [[reviewed]] would drop
      * it.
      */
    def briefed(
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Brief
    )(using FlowContext): Sessioned[B, PlanWithBrief] =
      val (sessionId, brief) =
        llm.withReadOnly.autonomous.run(instructions, session = sp.sessionId)
      val trimmed = brief.trim
      // A blank brief is a planning failure, and would render a `## Brief`
      // section that `parse` drops — fail rather than persist a degenerate plan.
      if trimmed.isEmpty then
        throw OrcaFlowException("brief turn produced an empty brief")
      Sessioned(sessionId, PlanWithBrief(sp.value, trimmed))

  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies the
    * picture; a planning failure is more useful as an explicit `fail(...)` from
    * the script.
    */
  given Announce[Plan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      val header =
        s"Planned ${plan.tasks.size} task$plural on branch '${plan.epicId}':"
      val body = plan.tasks.map(t => s"  - ${t.title}").mkString("\n")
      s"$header\n$body"

  /** Mark the task with `title` complete in the plan stored at `file`. Reads
    * the file, applies the change, writes it back. Use after a task is
    * committed so a subsequent run resumes at the next pending task.
    */
  def persistComplete(file: os.Path, title: Title): Unit =
    val current = parse(os.read(file))
    val updated = current.markComplete(title)
    os.write.over(file, render(updated))

  /** Acquire a persistent plan: resume from `file` if it exists, otherwise
    * evaluate `generate` (typically `Plan.autonomous.from(...).value`, or a
    * `.reviewed(...).briefed(...).value` chain) and lay down the branch +
    * on-disk plan for a fresh run.
    *
    * `generate` is a bare `=> PlanLike`, not a `Sessioned`: on the resume
    * branch there's no planning session to carry, so this helper can't expose
    * one consistently. Callers that want session reuse should drive the grid
    * operation directly instead of through `recoverOrCreate`; flows here mint a
    * fresh implementer session via `llm.newSession` (so `.value` the planning
    * result).
    *
    * `stashMessage` is used when a fresh start finds a dirty tree; pass a
    * flow-specific string so `git stash list` is searchable.
    */
  def recoverOrCreate(
      file: os.Path,
      stashMessage: String = "orca: starting work"
  )(generate: => PlanLike)(using ctx: FlowContext): PlanLike =
    recover(file).getOrElse:
      // ensureClean *before* generate so the planner sees a known-clean
      // tree (and the "stashed pending changes" Step only fires when the
      // user actually had pre-existing dirty edits, not when the planner
      // itself wrote files — `Plan.autonomous.from` runs read-only).
      val _ = ctx.git.ensureClean(stashMessage)
      val plan = generate
      ctx.git.checkoutOrCreate(plan.epicId)
      os.write.over(file, render(plan), createFolders = true)
      plan

  /** Resume from a previously-persisted plan. Returns `Some(plan)` when `file`
    * exists, with the working tree cleaned (any pending edits stashed; the user
    * can `git stash pop` afterwards) and the working copy attached to
    * `plan.epicId`. Returns `None` when no file exists — the caller decides
    * whether to generate a fresh plan ([[recoverOrCreate]] does that
    * automatically) or treat the absence as a hard failure.
    */
  def recover(file: os.Path)(using ctx: FlowContext): Option[PlanLike] =
    if !os.exists(file) then None
    else
      // Snapshot the file before stashing so an untracked plan file (one
      // created on disk but never committed — the common crash-before-first-
      // task-commit case) survives `ensureClean -u`. After the stash, if the
      // file is gone, restore it from the snapshot. If it's still there,
      // we trust the post-stash version — a tracked+dirty plan file should
      // resume from the committed contents, not the in-progress edits.
      val snapshot = os.read(file)
      val _ = ctx.git.ensureClean("orca: pre-recovery stash")
      val source =
        if os.exists(file) then os.read(file)
        else
          os.write.over(file, snapshot, createFolders = true)
          snapshot
      val plan = parse(source)
      ctx.git.checkoutOrCreate(plan.epicId)
      ctx.emit(
        OrcaEvent.Step(
          s"Recovered plan at $file (${plan.tasks.size} task(s), ${plan.tasks.count(_.completed)} already complete)"
        )
      )
      Some(plan)

  /** Per-task implementation loop with on-disk progress + commits.
    *
    * For each incomplete task in `plan`:
    *
    *   1. Calls `body(task)` — the caller's implement-and-review work. 2. Ticks
    *      the task's `Status: [x]` in `file`. 3. Makes one `task: <title>` git
    *      commit covering both the body's changes and the checkbox tick.
    *
    * After the last task: removes `file` and makes a `chore: remove
    * <file.last>` cleanup commit (skipped if the file was never tracked).
    * Bodies that throw abort the loop with the partial plan still on disk, so a
    * subsequent run resumes at the first incomplete task.
    *
    * Sibling of [[orca.review.reviewAndFixLoop]] — that one drives the
    * review-and-fix iteration within a task; this one drives task-by-task
    * progress across the whole plan.
    */
  def implementTaskLoop(file: os.Path, plan: PlanLike)(body: Task => Unit)(using
      ctx: FlowContext
  ): Unit =
    runTaskLoop(
      initial = plan,
      advance = (_, t) =>
        persistComplete(file, t.title)
        val next = parse(os.read(file))
        // Defense in depth: persistComplete + a clean re-read should always
        // advance past the just-processed title. If it didn't, the on-disk plan
        // diverged from what we just wrote — surface it instead of spinning.
        if next.firstIncomplete.map(_.title).contains(t.title) then
          throw OrcaFlowException(
            s"implementTaskLoop: task '${t.title.value}' is still the first incomplete entry " +
              s"after persistComplete. The plan file at $file may have been " +
              "edited so the title still matches an unchecked task."
          )
        next
      ,
      // Cleanup commit only fires if there's actually a file to remove —
      // skipping the no-op branch avoids a wasted `git add -A` subprocess and
      // a misleading "chore: remove …" commit-message intent when the file
      // never existed (e.g. caller pre-removed it).
      cleanup = () =>
        if os.exists(file) then
          val _ = os.remove(file)
          // `NothingToCommit` swallowed so a `.gitignore`d plan dir (untracked
          // file → no diff after removal) doesn't crash the whole run.
          val _ = ctx.git.commit(s"chore: remove ${file.last}")
    )(body)

  /** In-memory variant of [[implementTaskLoop]] for flows that aren't
    * resumable: same per-task `task: <title>` commit cadence, but completion is
    * tracked in memory (no plan file, no chore-remove commit). Use when the
    * surrounding flow has its own non-restartable state machine and a
    * `.orca/plan-*.md` file would just be dead weight (e.g. a bugfix flow whose
    * earlier stages — triage, CI red, repro verification — can't be resumed
    * from a plan-file alone).
    */
  def implementTaskLoop(plan: PlanLike)(body: Task => Unit)(using
      FlowContext
  ): Unit =
    runTaskLoop(
      initial = plan,
      advance = (cur, t) => cur.markComplete(t.title),
      cleanup = () => ()
    )(body)

  private def runTaskLoop(
      initial: PlanLike,
      advance: (PlanLike, Task) => PlanLike,
      cleanup: () => Unit
  )(body: Task => Unit)(using ctx: FlowContext): Unit =
    @scala.annotation.tailrec
    def loop(current: PlanLike): Unit = current.firstIncomplete match
      case None => ()
      case Some(t) =>
        body(t)
        val next = advance(current, t)
        // `NothingToCommit` is non-fatal here: a body that produced only
        // gitignored output (the plan-file tick when `.orca/` is in
        // `.gitignore`, or a no-op task by design) shouldn't abort the
        // loop and leave the next run skipping a task on the strength of
        // an on-disk tick alone. Same swallow the cleanup commit already
        // does. Surface the swallow as a Step so a body that silently
        // produced nothing (e.g. an LLM turn that ended without edits)
        // is still visible in the event log.
        ctx.git.commit(s"task: ${t.title}") match
          case Right(_) => ()
          case Left(_) =>
            ctx.emit(
              OrcaEvent.Step(
                s"task '${t.title.value}' produced no tracked changes — advancing without commit"
              )
            )
        loop(next)
    loop(initial)
    cleanup()

  /** Parse a plan from its markdown representation, auto-detecting a trailing
    * `## Brief` section: present → [[PlanWithBrief]], absent → [[Plan]]. Strict
    * on the plan part — throws [[PlanParseException]] on any deviation from the
    * `# Plan:` / `## Task:` schema. CRLF line endings and a leading BOM are
    * normalised first.
    */
  def parse(markdown: String): PlanLike =
    val normalised = markdown.stripPrefix("\uFEFF").replace("\r\n", "\n")
    val (planPart, brief) = splitBrief(normalised)
    val plan = parsePlan(planPart)
    brief.fold[PlanLike](plan)(b => PlanWithBrief(plan, b))

  /** Render a plan back into the on-disk format. Output round-trips through
    * [[parse]] without information loss; we use this to write the file when
    * first generated and again when a task's status flips. A [[PlanWithBrief]]
    * appends its brief as a trailing `## Brief` section.
    */
  def render(plan: PlanLike): String = plan match
    case p: Plan => renderPlan(p)
    case PlanWithBrief(p, brief) =>
      s"${renderPlan(p)}\n## Brief\n\n${brief.stripLineEnd}\n"

  // --- Brief section (rendered last, parsed first) ---

  private val BriefHeaderPattern = "^##\\s+Brief\\s*$".r

  /** Split a normalised document into its plan part and optional brief. The
    * brief is the text after the first `## Brief` heading that follows the
    * tasks — searching only past the first `## Task:` stops a stray `## Brief`
    * in the description from swallowing them, while still letting the brief
    * body carry its own `##` headings.
    */
  private def splitBrief(normalised: String): (String, Option[String]) =
    val lines = normalised.linesIterator.toList
    val afterFirstTask = lines.indexWhere(TaskHeaderPattern.matches) + 1
    lines.indexWhere(BriefHeaderPattern.matches, afterFirstTask) match
      case -1 => (normalised, None)
      case i  =>
        // Drop the blank line `render` writes after the heading; keep the rest
        // verbatim (a brief may be indented).
        val brief =
          lines.drop(i + 1).dropWhile(_.isEmpty).mkString("\n").stripLineEnd
        (
          lines.take(i).mkString("\n"),
          if brief.isEmpty then None else Some(brief)
        )

  private def renderPlan(plan: Plan): String =
    val header = s"# Plan: ${plan.epicId}\n"
    val descriptionBlock =
      if plan.description.trim.isEmpty then ""
      else s"\n${plan.description.stripLineEnd}\n"
    val body = plan.tasks
      .map: t =>
        val checkbox = if t.completed then "[x]" else "[ ]"
        s"\n## Task: ${t.title}\nStatus: $checkbox\n\n${t.description.stripLineEnd}\n"
      .mkString
    header + descriptionBlock + body

  // --- Parser internals ---

  private val HeaderPattern = "^# Plan:\\s*(\\S.*)$".r
  private val TaskHeaderPattern = "^## Task:\\s*(\\S.*)$".r
  private val StatusPattern = "^Status:\\s*\\[(.)\\]\\s*$".r

  private def parsePlan(planMarkdown: String): Plan =
    val lines = planMarkdown.linesIterator.toList
    val epicId = parseHeader(lines)
    val description = parseDescription(lines)
    val taskBlocks = splitTaskBlocks(lines)
    if taskBlocks.isEmpty then throw PlanParseException("Plan has no tasks")
    Plan(epicId, description, taskBlocks.map(parseTask))

  private def parseHeader(lines: List[String]): String =
    lines.find(_.trim.nonEmpty) match
      case Some(HeaderPattern(id)) => id.trim
      case other =>
        throw PlanParseException(
          s"Expected first non-blank line to match `# Plan: <epicId>`; got: ${other.getOrElse("(empty file)")}"
        )

  /** Description sits between the `# Plan:` header and the first `## Task:`
    * heading. Empty when the file goes straight from the header into tasks.
    */
  private def parseDescription(lines: List[String]): String =
    val afterHeader = lines.dropWhile(l => !HeaderPattern.matches(l)).drop(1)
    afterHeader
      .takeWhile(l => !TaskHeaderPattern.matches(l))
      .mkString("\n")
      .trim

  private def splitTaskBlocks(lines: List[String]): List[List[String]] =
    val blocks = collection.mutable.ListBuffer[List[String]]()
    var current = collection.mutable.ListBuffer[String]()
    var inTask = false
    for line <- lines do
      if TaskHeaderPattern.matches(line) then
        if inTask then blocks += current.toList
        current = collection.mutable.ListBuffer(line)
        inTask = true
      else if inTask then current += line
    if inTask then blocks += current.toList
    blocks.toList

  private def parseTask(block: List[String]): Task =
    val title = block.headOption match
      case Some(TaskHeaderPattern(t)) => t.trim
      case _ =>
        throw PlanParseException(
          s"Task block doesn't start with `## Task: <title>`: ${block.headOption.getOrElse("")}"
        )
    val rest = block.tail.dropWhile(_.trim.isEmpty)
    val (statusLine, afterStatus) = rest.headOption match
      case Some(line @ StatusPattern(_)) => (line, rest.tail)
      case _ =>
        throw PlanParseException(
          s"Task '$title' is missing a `Status: [ ]` / `Status: [x]` line"
        )
    val completed = statusLine match
      case StatusPattern(" ") => false
      case StatusPattern("x") => true
      case StatusPattern(other) =>
        throw PlanParseException(
          s"Task '$title' has unrecognised status checkbox '$other'"
        )
    val description = afterStatus.mkString("\n").trim
    if description.isEmpty then
      throw PlanParseException(s"Task '$title' has no prompt body")
    Task(title = Title(title), description = description, completed = completed)

object PlanWithBrief:

  /** Reuse [[Plan]]'s announce — the brief is internal context, not worth
    * surfacing in the event log on its own.
    */
  given Announce[PlanWithBrief] = Announce.from: pwb =>
    summon[Announce[Plan]].message(pwb.plan).getOrElse("")

  extension [B <: BackendTag](sp: Sessioned[B, PlanWithBrief])
    /** Resume the planning session to review the plan *and* its brief together,
      * returning the improved [[PlanWithBrief]]. The counterpart to
      * [[Sessioned.reviewed]] on a bare plan, reachable as `…briefed.reviewed`.
      */
    def reviewed(
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Review
    )(using FlowContext): Sessioned[B, PlanWithBrief] =
      val (sessionId, improved) = llm.withReadOnly
        .resultAs[PlanWithBrief]
        .autonomous
        .run(
          s"$instructions\n\n${Plan.render(sp.value)}",
          session = sp.sessionId
        )
      Sessioned(sessionId, improved)

class PlanParseException(message: String) extends RuntimeException(message)
