package orca.tools

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.{OrcaFlowException, WorkspaceWrite}
import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.JsonData
import orca.subprocess.{CliResult, CliRunner}
import ox.sleep
import ox.resilience.{ResultPolicy, RetryConfig, retry}
import ox.scheduling.Schedule

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** A handle to an open pull request. `derives JsonData` so a `stage` can record
  * and replay a `PrHandle` result (ADR 0018 §3.2).
  */
case class PrHandle(owner: String, repo: String, number: Int) derives JsonData:
  /** Canonical GitHub short-form `<owner>/<repo>#<number>`. */
  def shortRef: String = s"$owner/$repo#$number"

  /** Browser URL for the PR. */
  def url: String = s"https://github.com/$owner/$repo/pull/$number"

case class IssueHandle(owner: String, repo: String, number: Int):
  /** Canonical GitHub short-form `<owner>/<repo>#<number>`. */
  def shortRef: String = s"$owner/$repo#$number"

object IssueHandle:
  private val ShortRefPattern =
    """\s*([^/\s]+)/([^#\s]+)#(\d+)\s*""".r

  /** Parse the canonical `<owner>/<repo>#<number>` short-form (surrounding
    * whitespace tolerated).
    */
  def parse(s: String): Either[String, IssueHandle] =
    s match
      case ShortRefPattern(owner, repo, number) =>
        Right(IssueHandle(owner, repo, number.toInt))
      case _ =>
        Left(s"expected '<owner>/<repo>#<number>', got: '$s'")

  /** Same as [[parse]] but throws [[OrcaFlowException]] on malformed input, so
    * the message bubbles up through the stage error path.
    */
  def parseOrThrow(s: String): IssueHandle =
    parse(s) match
      case Right(handle) => handle
      case Left(msg)     => throw OrcaFlowException(msg)

case class Comment(author: String, body: String)

/** Snapshot of an issue's top-level fields. Comments live on a separate
  * endpoint and are read via [[GitHubTool.readIssueComments]].
  */
case class Issue(
    title: String,
    body: String,
    author: String,
    state: String
)

enum BuildOutcome:
  case Pending
  case Success
  case Failure

/** @param checkCount
  *   number of entries in the PR's `statusCheckRollup`, kept as a structured
  *   fact so callers need not re-derive it from the rendered `log`.
  */
case class BuildStatus(outcome: BuildOutcome, log: String, checkCount: Int)

/** Total classification of a single [[GhCheck]] entry, parsed once at the DTO
  * boundary so the rest of the tool reasons about a closed, named shape.
  *
  *   - [[CheckState.Pending]]: still running, or a required check that hasn't
  *     reported yet (including GitHub's legacy `EXPECTED` commit status).
  *   - [[CheckState.Success]]: completed with a successful conclusion (or
  *     legacy `state = SUCCESS`).
  *   - [[CheckState.Failure]]: completed with a recognised non-successful
  *     conclusion (or legacy failure state).
  *   - [[CheckState.Unknown]]: a shape [[OsGitHubTool.stateOf]] doesn't
  *     recognise. Kept distinct from [[CheckState.Failure]] so it can be
  *     surfaced in [[BuildStatus.log]] rather than read as a confirmed failure.
  */
enum CheckState:
  case Pending
  case Success
  case Failure
  case Unknown(raw: String)

/** Recoverable [[GitHubTool.createPr]] failure modes. Common when re-running a
  * flow against an already-pushed branch. Other gh failures (auth, network)
  * remain thrown.
  */
sealed abstract class PrCreateFailed(message: String)
    extends OrcaFlowException(message)

final class PrAlreadyExists
    extends PrCreateFailed(
      "a pull request for the current branch already exists"
    )

final class NoCommitsToPr
    extends PrCreateFailed(
      "no commits to open a pull request from — push the branch first"
    )

/** Common parent for recoverable [[GitHubTool.waitForBuild]] failure modes:
  * returned as a `Left`, but subclass `OrcaFlowException` so a caller's
  * `.orThrow` still surfaces them.
  */
sealed abstract class BuildWaitFailed(message: String)
    extends OrcaFlowException(message)

/** Returned when the overall `waitForBuild` deadline elapsed while the build
  * was still pending (CI running, just slowly).
  */
final class BuildTimedOut(timeout: FiniteDuration)
    extends BuildWaitFailed(s"build did not finish within $timeout")

/** Returned when no CI check was ever registered against the PR after
  * `noChecksGrace` — typically the target repo has no CI workflow set up.
  * Distinct from [[BuildTimedOut]] so the caller can give a more actionable
  * error.
  */
final class NoChecksConfigured(grace: FiniteDuration)
    extends BuildWaitFailed(
      s"no CI checks registered against the PR after $grace — most likely the " +
        "repo has no CI workflow configured"
    )

/** GitHub adapter usable from flow scripts — the handle behind the `gh`
  * accessor. Creates pull requests, reads issues and their comments, reads and
  * writes PR comments, and polls GitHub's check-run status.
  */
trait GitHubTool:
  def createPr(title: String, body: String)(using
      WorkspaceWrite
  ): Either[PrCreateFailed, PrHandle]

  /** Replace an existing PR's title and body, e.g. to refresh a PR opened with
    * a tentative description once later work is pushed.
    */
  def updatePr(pr: PrHandle, title: String, body: String)(using
      WorkspaceWrite
  ): Unit

  /** Fetch the issue's title, body, author, and state. */
  def readIssue(issue: IssueHandle): Issue

  /** Fetch the conversation comments on an issue, in posting order. */
  def readIssueComments(issue: IssueHandle): List[Comment]

  /** Fetch the conversation comments on a PR (issue-style comments, not
    * line-level review comments — those live on a separate endpoint).
    */
  def readPrComments(pr: PrHandle): List[Comment]

  /** Post a top-level issue-style comment on a pull request (not a line-level
    * review comment).
    */
  def writeComment(pr: PrHandle, body: String)(using WorkspaceWrite): Unit

  /** Post a top-level comment on an issue. */
  def writeComment(issue: IssueHandle, body: String)(using WorkspaceWrite): Unit

  /** Idempotent comment on a PR. Updates (via REST PATCH) the first existing
    * comment whose body contains `marker`, else creates a new one with `body`
    * followed by `marker` on a separate line. The caller embeds `marker` as an
    * HTML comment (e.g. `<!-- orca:<hash>:<purpose> -->`) so a re-run finds and
    * updates its own comment instead of duplicating it. Plain [[writeComment]]
    * stays append-only.
    */
  def upsertComment(pr: PrHandle, marker: String, body: String)(using
      WorkspaceWrite
  ): Unit

  /** Idempotent comment on an issue. Same find/update/create semantics as
    * [[upsertComment(PrHandle, String, String)]].
    */
  def upsertComment(issue: IssueHandle, marker: String, body: String)(using
      WorkspaceWrite
  ): Unit

  /** Aggregate status of the checks attached to `pr`.
    *
    * Implementations MUST treat an empty check list as `BuildOutcome.Pending`,
    * not `Success`: GitHub returns an empty rollup for several seconds after a
    * push while the workflow is being registered, so collapsing to `Success`
    * would produce a false "build green". [[waitForBuild]]'s grace period
    * disambiguates the "no CI configured" case after the fact.
    */
  def buildStatus(pr: PrHandle): BuildStatus

  /** Poll [[buildStatus]] every `pollInterval` (impl-defined) until the build
    * reaches a terminal outcome or one of two timeouts fires:
    *
    *   - `timeout` is the overall deadline. When it elapses while the build is
    *     still pending, returns `Left(BuildTimedOut)`.
    *   - `noChecksGrace` catches the "repo has no CI workflow configured" case:
    *     when no check has registered after that grace, returns
    *     `Left(NoChecksConfigured)` immediately rather than burning the rest of
    *     `timeout`. Defaults to 90 seconds.
    */
  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration,
      noChecksGrace: FiniteDuration = 90.seconds
  ): Either[BuildWaitFailed, BuildStatus]

/** GitHubTool implementation that shells out to the `gh` CLI via a `CliRunner`.
  *
  * `events` lets the tool publish a [[OrcaEvent.Step]] when a PR is opened so
  * the URL surfaces in the event log. Defaults to `OrcaListener.noop`.
  */
private[orca] class OsGitHubTool(
    cli: CliRunner,
    workDir: os.Path = os.pwd,
    pollInterval: FiniteDuration = 30.seconds,
    events: OrcaListener = OrcaListener.noop,
    readRetry: Schedule = OsGitHubTool.defaultReadRetry
) extends GitHubTool:

  import OsGitHubTool.*

  /** Retry policy for the idempotent, read-only `gh` invocations ([[ghRead]]):
    * absorb a transient gh/GitHub failure (surfaced as a non-zero `gh` exit,
    * i.e. an `OrcaFlowException`) under a bounded backoff.
    */
  private val readRetryConfig: RetryConfig[Throwable, String] =
    RetryConfig(
      readRetry,
      ResultPolicy.retryWhen[Throwable, String](
        _.isInstanceOf[OrcaFlowException]
      )
    )

  private val PrUrlPattern =
    """https://github\.com/([^/]+)/([^/]+)/pull/(\d+)""".r

  def createPr(title: String, body: String)(using
      WorkspaceWrite
  ): Either[PrCreateFailed, PrHandle] =
    // Inspect exit code + stderr ourselves to split the recoverable "branch
    // already has a PR" / "no commits to push" cases from genuine system
    // failures, so this uses `runGhResult` (raw result) rather than
    // `ghRead`/`ghMutate` (which abort on non-zero exit).
    val result = runGhResult("pr", "create", "--title", title, "--body", body)
    if result.exitCode == 0 then
      val output = result.stdout.trim
      PrUrlPattern.findFirstMatchIn(output) match
        case Some(m) =>
          val pr = PrHandle(m.group(1), m.group(2), m.group(3).toInt)
          events.onEvent(OrcaEvent.Step(s"Opened PR: ${pr.url}"))
          Right(pr)
        case None =>
          throw OrcaFlowException(
            s"Unexpected output from gh pr create: $output"
          )
    else
      val combined = result.stdout + "\n" + result.stderr
      if OsGitHubTool.isPrAlreadyExists(combined) then
        // Look up the existing open PR rather than failing — crash-safe for
        // flows that may re-enter a "push + open PR" stage.
        findOpenPr(currentBranchGit()) match
          case Some(pr) =>
            events.onEvent(OrcaEvent.Step(s"Reusing existing PR: ${pr.url}"))
            Right(pr)
          case None => Left(new PrAlreadyExists)
      else if OsGitHubTool.isNoCommitsToPr(combined) then
        Left(new NoCommitsToPr)
      else fail("gh pr create", result)

  /** Resolve the current branch name via `git rev-parse --abbrev-ref HEAD`.
    * Carries [[OsGitTool.nonInteractiveEnv]] so an ssh/credential prompt can't
    * hang a flow.
    */
  private def currentBranchGit(): String =
    val result = cli.run(
      Seq("git", "rev-parse", "--abbrev-ref", "HEAD"),
      env = OsGitTool.nonInteractiveEnv,
      cwd = workDir
    )
    if result.exitCode == 0 then result.stdout.trim
    else fail("git rev-parse", result)

  /** Find the first open PR whose head branch matches `head`, or `None`.
    * Head-only matching suffices: a branch has at most one open PR per base,
    * and `createPr` runs from an orca-managed branch.
    */
  private def findOpenPr(head: String): Option[PrHandle] =
    val output = ghRead(
      "pr",
      "list",
      "--head",
      head,
      "--state",
      "open",
      "--json",
      "number,url"
    )
    readFromString[List[GhPrListJson]](output).headOption.flatMap: entry =>
      PrUrlPattern
        .findFirstMatchIn(entry.url)
        .map: m =>
          PrHandle(m.group(1), m.group(2), m.group(3).toInt)

  def readIssue(issue: IssueHandle): Issue =
    val output = ghRead(
      "api",
      s"repos/${issue.owner}/${issue.repo}/issues/${issue.number}"
    )
    val parsed = readFromString[GhIssueJson](output)
    Issue(
      title = parsed.title,
      body = parsed.body.getOrElse(""),
      author = parsed.user.login,
      state = parsed.state
    )

  def readIssueComments(issue: IssueHandle): List[Comment] =
    readCommentsAt(issue.owner, issue.repo, issue.number)

  def readPrComments(pr: PrHandle): List[Comment] =
    // The `/issues/{n}/comments` endpoint returns conversation comments for
    // both issues and PRs. Line-level review comments live at
    // `/pulls/{n}/comments` and aren't covered here.
    readCommentsAt(pr.owner, pr.repo, pr.number)

  private def readCommentsAt(
      owner: String,
      repo: String,
      number: Int
  ): List[Comment] =
    val output = ghRead(
      "api",
      "--paginate",
      s"repos/$owner/$repo/issues/$number/comments"
    )
    readFromString[List[GhCommentJson]](output).map: c =>
      Comment(author = c.user.login, body = c.body)

  def updatePr(pr: PrHandle, title: String, body: String)(using
      WorkspaceWrite
  ): Unit =
    // Use the REST API directly rather than `gh pr edit`: the latter runs a
    // GraphQL query selecting `projectCards`, which fails on repos where GitHub
    // has sunset Projects (classic). The REST PATCH endpoint doesn't touch
    // projects.
    val _ = ghMutate(
      "api",
      "-X",
      "PATCH",
      s"repos/${pr.owner}/${pr.repo}/pulls/${pr.number}",
      "-f",
      s"title=$title",
      "-f",
      s"body=$body"
    )
    events.onEvent(OrcaEvent.Step(s"Updated PR: ${pr.url}"))

  def writeComment(pr: PrHandle, body: String)(using WorkspaceWrite): Unit =
    val _ = ghMutate(
      "pr",
      "comment",
      pr.number.toString,
      "--repo",
      s"${pr.owner}/${pr.repo}",
      "--body",
      body
    )

  def writeComment(issue: IssueHandle, body: String)(using
      WorkspaceWrite
  ): Unit =
    val _ = ghMutate(
      "issue",
      "comment",
      issue.number.toString,
      "--repo",
      s"${issue.owner}/${issue.repo}",
      "--body",
      body
    )

  def upsertComment(pr: PrHandle, marker: String, body: String)(using
      WorkspaceWrite
  ): Unit =
    upsertCommentAt(pr.owner, pr.repo, pr.number, marker, body):
      writeComment(pr, _)

  def upsertComment(issue: IssueHandle, marker: String, body: String)(using
      WorkspaceWrite
  ): Unit =
    upsertCommentAt(issue.owner, issue.repo, issue.number, marker, body):
      writeComment(issue, _)

  /** Shared upsert logic for both PR and issue targets. PATCHes the first
    * comment containing `marker`, else delegates to `createFn`. The stored body
    * is `<body>\n\n<marker>` so future re-runs can locate the same comment.
    */
  private def upsertCommentAt(
      owner: String,
      repo: String,
      number: Int,
      marker: String,
      body: String
  )(createFn: String => Unit): Unit =
    val markedBody = s"$body\n\n$marker"
    fetchIdentifiedComments(owner, repo, number).find(
      _.body.contains(marker)
    ) match
      case Some(existing) =>
        patchComment(owner, repo, existing.id, markedBody)
      case None =>
        createFn(markedBody)

  /** Fetch comments for a PR/issue with their GitHub-issued numeric ids, for
    * [[upsertCommentAt]]. The ids never leak into the public API.
    */
  private def fetchIdentifiedComments(
      owner: String,
      repo: String,
      number: Int
  ): List[GhIdentifiedCommentJson] =
    val output = ghRead(
      "api",
      "--paginate",
      s"repos/$owner/$repo/issues/$number/comments"
    )
    readFromString[List[GhIdentifiedCommentJson]](output)

  /** PATCH an existing issue/PR comment body via the REST API. */
  private def patchComment(
      owner: String,
      repo: String,
      id: Long,
      body: String
  ): Unit =
    val _ = ghMutate(
      "api",
      "-X",
      "PATCH",
      s"repos/$owner/$repo/issues/comments/$id",
      "-f",
      s"body=$body"
    )

  def buildStatus(pr: PrHandle): BuildStatus =
    val output = ghRead(
      "pr",
      "view",
      pr.number.toString,
      "--repo",
      s"${pr.owner}/${pr.repo}",
      "--json",
      "statusCheckRollup"
    )
    val rollup = readFromString[GhCheckRollup](output)
    val outcome = aggregateOutcome(rollup.statusCheckRollup)
    val log = rollup.statusCheckRollup
      .map: c =>
        val tag = OsGitHubTool.stateOf(c) match
          // Called out explicitly so the log distinguishes an unrecognised
          // shape from a real CI failure.
          case CheckState.Unknown(raw) => s"unknown ($raw)"
          case _ =>
            c.conclusion.orElse(c.state).orElse(c.status).getOrElse("?")
        s"${c.name.getOrElse("?")}: $tag"
      .mkString("\n")
    BuildStatus(outcome, log, checkCount = rollup.statusCheckRollup.size)

  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration,
      noChecksGrace: FiniteDuration = 90.seconds
  ): Either[BuildWaitFailed, BuildStatus] =
    val start = System.nanoTime()
    val deadline = start + timeout.toNanos
    val noChecksDeadline = start + noChecksGrace.toNanos

    @scala.annotation.tailrec
    def loop(sawAnyCheck: Boolean): Either[BuildWaitFailed, BuildStatus] =
      // `buildStatus` already retries transient gh/GitHub blips internally; a
      // failure here means the read failed past that budget.
      val status = buildStatus(pr)
      val now = System.nanoTime()
      // Sticky watermark: once one non-empty rollup is seen, the "no CI
      // configured" hypothesis is disproven, so a later transient empty rollup
      // can't fire NoChecksConfigured. Driven by structured `checkCount`, not
      // the rendered `log`.
      val seen = sawAnyCheck || status.checkCount > 0
      if status.outcome != BuildOutcome.Pending then Right(status)
      else if !seen && now >= noChecksDeadline then
        Left(new NoChecksConfigured(noChecksGrace))
      else if now >= deadline then Left(new BuildTimedOut(timeout))
      else
        sleep(pollInterval)
        loop(seen)

    loop(sawAnyCheck = false)

  /** Run `gh` once and return the raw [[CliResult]] — the single point every gh
    * invocation funnels through. Used directly only by [[createPr]] (which
    * inspects the exit code itself); other calls go through [[ghRead]] or
    * [[ghMutate]].
    */
  private def runGhResult(args: String*): CliResult =
    cli.run("gh" +: args, cwd = workDir)

  /** Run `gh` once, returning stdout or aborting on a non-zero exit. Not called
    * directly — always via [[ghRead]] or [[ghMutate]], so the retry-vs-no-retry
    * choice is explicit at each call site.
    */
  private def runGh(args: String*): String =
    val result = runGhResult(args*)
    if result.exitCode != 0 then fail(s"gh ${args.mkString(" ")}", result)
    result.stdout

  /** Abort with a uniform message for an unrecoverable CLI failure. Callers
    * handle the expected non-zero exits (PR already exists, no commits) as
    * `Left`s before reaching here.
    */
  private def fail(label: String, result: CliResult): Nothing =
    throw OrcaFlowException(
      s"$label failed (exit ${result.exitCode}): ${result.stderr}"
    )

  /** Run an **idempotent read** (`api` GET, `pr view`, `pr list`), retrying a
    * transient failure under [[readRetryConfig]] — safe because it has no side
    * effect.
    */
  private def ghRead(args: String*): String =
    retry(readRetryConfig)(runGh(args*))

  /** Run a **mutating** `gh` call exactly once, deliberately NOT retried: a
    * retry after a lost response would double the side effect (duplicate
    * comment / PR edit). `pr create` idempotency is handled separately in
    * [[createPr]].
    */
  private def ghMutate(args: String*): String = runGh(args*)

private[orca] object OsGitHubTool:

  /** Default retry for idempotent read-only `gh` calls: bounded exponential
    * backoff. Injectable on the constructor so tests can use a no-delay
    * schedule.
    */
  val defaultReadRetry: Schedule =
    Schedule.exponentialBackoff(1.second).maxRetries(4)

  // --- Recoverable `gh pr create` stderr/stdout predicates ---
  //
  // gh has no machine-readable signal for these cases, so `createPr` matches
  // gh's human-readable output (combined stdout+stderr). That output is UI
  // text, not a contract, so the matchers are centralised, unit-tested, and
  // kept lenient. Each case-folds its input, so callers pass gh's output
  // verbatim.

  /** True when `gh pr create` reported that an open PR already exists for the
    * head branch — the case `createPr` resolves by reusing the existing PR.
    */
  private[tools] def isPrAlreadyExists(combined: String): Boolean =
    combined.toLowerCase.contains("already exists")

  /** True when `gh pr create` reported there is nothing to open a PR from (no
    * commits ahead of base, or branch not pushed).
    */
  private[tools] def isNoCommitsToPr(combined: String): Boolean =
    val lower = combined.toLowerCase
    lower.contains("no commits") || lower.contains("must first push")

  private val StatusCompleted = "COMPLETED"
  private val SuccessfulConclusions = Set("SUCCESS", "NEUTRAL", "SKIPPED")
  // The documented failing CheckRun `conclusion` values. Named explicitly
  // (rather than "anything that isn't a success") so a conclusion GitHub adds
  // later falls through to `CheckState.Unknown` rather than reading as a
  // confirmed failure.
  private val FailureConclusions = Set(
    "FAILURE",
    "CANCELLED",
    "TIMED_OUT",
    "ACTION_REQUIRED",
    "STALE",
    "STARTUP_FAILURE"
  )
  private val LegacyStateSuccess = "SUCCESS"
  private val LegacyStatePending = "PENDING"
  // Legacy `StatusState`: a required status context registered but not yet
  // reported — distinct from `PENDING` (actively running); both mean "not
  // resolved yet".
  private val LegacyStateExpected = "EXPECTED"
  private val LegacyFailureStates = Set("FAILURE", "ERROR")

  /** Reduce a list of check entries to a single outcome. Empty list is treated
    * as Pending: just after a push GitHub returns zero checks for several
    * seconds while the workflow registers, so collapsing empty to Success would
    * surface a false "build green". A repo with no CI at all stays Pending
    * until `waitForBuild`'s `noChecksGrace` converts it to
    * `NoChecksConfigured`.
    */
  def aggregateOutcome(checks: List[GhCheck]): BuildOutcome =
    if checks.isEmpty then BuildOutcome.Pending
    else
      val states = checks.map(stateOf)
      if states.contains(CheckState.Pending) then BuildOutcome.Pending
      else if states.forall(_ == CheckState.Success) then BuildOutcome.Success
      // Both `Failure` and unrecognised `Unknown` collapse to
      // `BuildOutcome.Failure` here, but `Unknown` stays distinguishable in
      // `BuildStatus.log`.
      else BuildOutcome.Failure

  /** Classify a single check entry into a total [[CheckState]]. `GhCheck`
    * mirrors two GitHub shapes (CheckRun's `status`/`conclusion`, and the
    * legacy commit-status `state`) in one flat DTO; this is the one place that
    * maps either shape to a [[CheckState]].
    */
  private[tools] def stateOf(c: GhCheck): CheckState =
    if c.status.exists(_ != StatusCompleted) then CheckState.Pending
    else if c.state.contains(LegacyStatePending) ||
      c.state.contains(LegacyStateExpected)
    then CheckState.Pending
    else if c.status.isEmpty && c.state.isEmpty && c.conclusion.isEmpty then
      CheckState.Pending
    else if c.conclusion.exists(SuccessfulConclusions.contains) then
      CheckState.Success
    else if c.state.contains(LegacyStateSuccess) then CheckState.Success
    else if c.conclusion.exists(FailureConclusions.contains) then
      CheckState.Failure
    else if c.state.exists(LegacyFailureStates.contains) then CheckState.Failure
    else CheckState.Unknown(rawShape(c))

  /** Render a check's raw field values for [[CheckState.Unknown]]'s payload, so
    * an unrecognised shape is diagnosable from the log without re-fetching.
    */
  private def rawShape(c: GhCheck): String =
    s"status=${c.status.getOrElse("none")} conclusion=${c.conclusion
        .getOrElse("none")} state=${c.state.getOrElse("none")}"
