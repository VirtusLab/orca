package orca.tools

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.{InStage, OrcaFlowException}
import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.JsonData
import orca.subprocess.{CliResult, CliRunner}
import ox.sleep
import ox.resilience.{ResultPolicy, RetryConfig, retry}
import ox.scheduling.Schedule

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** A handle to an open pull request. `derives JsonData` so `stage` can record
  * and replay a `PrHandle` result — e.g. when a push-and-open-PR stage is the
  * checkpoint before a CI wait (ADR 0018 §3.2).
  */
case class PrHandle(owner: String, repo: String, number: Int) derives JsonData:
  /** `<owner>/<repo>#<number>` — the canonical GitHub short-form. Used in
    * commit messages, PR descriptions (`Closes …`), and log output.
    */
  def shortRef: String = s"$owner/$repo#$number"

  /** Browser URL for the PR, the same value `gh pr view --web` would open. */
  def url: String = s"https://github.com/$owner/$repo/pull/$number"

/** Lightweight reference to a GitHub issue. The number is what `gh issue view
  * <n>` shows; the owner/repo route the API call.
  */
case class IssueHandle(owner: String, repo: String, number: Int):
  /** `<owner>/<repo>#<number>` — the canonical GitHub short-form. Used in
    * commit messages, PR descriptions (`Closes …`), and log output.
    */
  def shortRef: String = s"$owner/$repo#$number"

object IssueHandle:
  private val ShortRefPattern =
    """\s*([^/\s]+)/([^#\s]+)#(\d+)\s*""".r

  /** Parse the canonical `<owner>/<repo>#<number>` short-form. Leading and
    * trailing whitespace are tolerated; everything else is rejected.
    */
  def parse(s: String): Either[String, IssueHandle] =
    s match
      case ShortRefPattern(owner, repo, number) =>
        Right(IssueHandle(owner, repo, number.toInt))
      case _ =>
        Left(s"expected '<owner>/<repo>#<number>', got: '$s'")

  /** Same as [[parse]] but throws [[OrcaFlowException]] on malformed input —
    * convenient for flow scripts that want the message to bubble up through the
    * stage error path the way `fail(...)` would.
    */
  def parseOrThrow(s: String): IssueHandle =
    parse(s) match
      case Right(handle) => handle
      case Left(msg)     => throw OrcaFlowException(msg)

case class Comment(author: String, body: String)

/** Snapshot of an issue's top-level fields — the bits a flow typically wants
  * when triaging or planning from an issue body. Comments live on a separate
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

case class BuildStatus(outcome: BuildOutcome, log: String)

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

/** Common parent for recoverable [[GitHubTool.waitForBuild]] failure modes —
  * both manifest as a `Left` rather than a thrown exception, but subclass
  * `OrcaFlowException` so a caller's `.orThrow` still surfaces them.
  */
sealed abstract class BuildWaitFailed(message: String)
    extends OrcaFlowException(message)

/** Returned when the overall `waitForBuild` deadline elapsed while the build
  * was still pending (real CI was running, just slowly). The caller can decide
  * whether to keep waiting, escalate to a human, or abort.
  */
final class BuildTimedOut(timeout: FiniteDuration)
    extends BuildWaitFailed(s"build did not finish within $timeout")

/** Returned when no CI check was ever registered against the PR after
  * `noChecksGrace`. Typically means the target repo has no CI workflow set up —
  * distinct from a real CI run that timed out. Surfaced as a separate type so
  * the caller can give a more actionable error message than "CI didn't finish".
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
      InStage
  ): Either[PrCreateFailed, PrHandle]

  /** Replace an existing PR's title and body. Used to refresh a PR opened with
    * a tentative description (e.g. when only a failing test had landed) once
    * later work — the fix — is pushed.
    */
  def updatePr(pr: PrHandle, title: String, body: String)(using InStage): Unit

  /** Fetch the issue's title, body, author, and state. */
  def readIssue(issue: IssueHandle): Issue

  /** Fetch the conversation comments on an issue (the comments the GitHub UI
    * shows under the body, in posting order).
    */
  def readIssueComments(issue: IssueHandle): List[Comment]

  /** Fetch the conversation comments on a PR (issue-style comments, not
    * line-level review comments — those live on a separate endpoint).
    */
  def readPrComments(pr: PrHandle): List[Comment]

  /** Post a top-level issue-style comment on a pull request (the comments the
    * GitHub UI shows under the description, not line-level review comments).
    */
  def writeComment(pr: PrHandle, body: String)(using InStage): Unit

  /** Post a top-level comment on an issue. Used by assess-then-act flows to
    * surface a follow-up question / critique / rebuff back to the reporter when
    * no PR will be opened.
    */
  def writeComment(issue: IssueHandle, body: String)(using InStage): Unit

  /** Idempotent comment on a PR. Finds the first existing comment whose body
    * contains `marker`, then updates it via a REST PATCH; if none is found,
    * creates a new comment with `body` followed by `marker` on a separate line.
    * The `marker` is an HTML comment the caller embeds (e.g. `<!--
    * orca:<hash>:<purpose> -->`) so a re-run can locate and update its own
    * comment instead of duplicating it. Plain [[writeComment]] stays
    * append-only.
    */
  def upsertComment(pr: PrHandle, marker: String, body: String)(using
      InStage
  ): Unit

  /** Idempotent comment on an issue. Same find/update/create semantics as
    * [[upsertComment(PrHandle, String, String)]].
    */
  def upsertComment(issue: IssueHandle, marker: String, body: String)(using
      InStage
  ): Unit

  /** Aggregate status of the checks attached to `pr`.
    *
    * Contract for the empty-rollup case: implementations MUST treat an empty
    * check list as `BuildOutcome.Pending`, not `Success`. GitHub returns an
    * empty rollup for several seconds after a push while the workflow is being
    * registered — collapsing to `Success` there races with CI startup and
    * produces a false "build green". The [[waitForBuild]] grace period is what
    * disambiguates the "no CI configured" case after the fact.
    */
  def buildStatus(pr: PrHandle): BuildStatus

  /** Poll [[buildStatus]] every `pollInterval` (impl-defined) until the build
    * reaches a terminal outcome or one of two timeouts fires:
    *
    *   - `timeout` is the overall deadline. When it elapses while the build is
    *     still pending, returns `Left(BuildTimedOut)`.
    *   - `noChecksGrace` catches the "repo has no CI workflow configured" case.
    *     When no check has registered after that grace, returns
    *     `Left(NoChecksConfigured)` immediately rather than burning the rest of
    *     `timeout`. Defaults to 90 seconds — long enough to absorb normal CI
    *     startup, short enough to give a useful error fast.
    */
  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration,
      noChecksGrace: FiniteDuration = 90.seconds
  ): Either[BuildWaitFailed, BuildStatus]

/** GitHubTool implementation that shells out to the `gh` CLI via a `CliRunner`.
  * `waitForBuild` polls `buildStatus` every `pollInterval` until a terminal
  * outcome or the caller-supplied timeout expires.
  *
  * `events` lets the tool publish a [[OrcaEvent.Step]] when a PR is opened so
  * the URL surfaces in the event log without the flow developer having to log
  * it. Optional — defaults to `OrcaListener.noop` so callers that don't wire a
  * dispatcher (unit tests, ad-hoc scripts) still work.
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
    * absorb a transient gh/GitHub failure (dropped connection, 5xx — surfaced
    * as a non-zero `gh` exit, i.e. an `OrcaFlowException`) under a bounded
    * backoff before the failure propagates.
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
      InStage
  ): Either[PrCreateFailed, PrHandle] =
    // Inspect exit code + stderr ourselves so we can split the recoverable
    // "branch already has a PR" / "no commits to push" cases out from
    // genuine system failures — so this goes through `runGhResult` (which
    // returns the raw result) rather than `ghRead`/`ghMutate` (which abort).
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
        // R24: look up the existing open PR rather than failing — crash-safe
        // for flows that may re-enter a "push + open PR" stage.
        findOpenPr(currentBranchGit()) match
          case Some(pr) =>
            events.onEvent(OrcaEvent.Step(s"Reusing existing PR: ${pr.url}"))
            Right(pr)
          case None => Left(new PrAlreadyExists)
      else if OsGitHubTool.isNoCommitsToPr(combined) then
        Left(new NoCommitsToPr)
      else fail("gh pr create", result)

  /** Resolve the current branch name via `git rev-parse --abbrev-ref HEAD`.
    * Used by [[createPr]] to pass the head branch to [[findOpenPr]]. Carries
    * [[OsGitTool.nonInteractiveEnv]] like every other git invocation in this
    * codebase, so an ssh/credential prompt on this one path can't hang a flow.
    */
  private def currentBranchGit(): String =
    val result = cli.run(
      Seq("git", "rev-parse", "--abbrev-ref", "HEAD"),
      env = OsGitTool.nonInteractiveEnv,
      cwd = workDir
    )
    if result.exitCode == 0 then result.stdout.trim
    else fail("git rev-parse", result)

  /** Find an open PR whose head branch matches `head`, using `gh pr list --head
    * <head> --state open --json number,url`. Returns the first match, or `None`
    * when no open PR is found. Uses [[ghRead]] (with retry) because this is an
    * idempotent read.
    *
    * Matching on head-only: this suffices in practice because a branch can only
    * have one open PR targeting any given base at a time, and `createPr` is
    * called from within an orca-managed branch where hijacking a stacked PR is
    * not expected.
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
    // GitHub's `/issues/{n}/comments` endpoint returns the conversation
    // comments for both issues and PRs (a PR is an issue in the data
    // model). Line-level review comments live at `/pulls/{n}/comments`
    // and aren't covered here.
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

  def updatePr(pr: PrHandle, title: String, body: String)(using InStage): Unit =
    // Use the REST API directly rather than `gh pr edit`: the latter runs a
    // GraphQL metadata query that selects `projectCards` before applying any
    // edit, which fails outright on repos where GitHub has sunset Projects
    // (classic). The REST PATCH endpoint doesn't touch projects.
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

  def writeComment(pr: PrHandle, body: String)(using InStage): Unit =
    val _ = ghMutate(
      "pr",
      "comment",
      pr.number.toString,
      "--repo",
      s"${pr.owner}/${pr.repo}",
      "--body",
      body
    )

  def writeComment(issue: IssueHandle, body: String)(using InStage): Unit =
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
      InStage
  ): Unit =
    upsertCommentAt(pr.owner, pr.repo, pr.number, marker, body):
      writeComment(pr, _)

  def upsertComment(issue: IssueHandle, marker: String, body: String)(using
      InStage
  ): Unit =
    upsertCommentAt(issue.owner, issue.repo, issue.number, marker, body):
      writeComment(issue, _)

  /** Shared upsert logic for both PR and issue targets. Fetches comments with
    * their ids, finds the first comment containing `marker`, then either
    * PATCHes that comment or delegates to `createFn` to post a new one. The
    * body stored (both on create and update) is `<body>\n\n<marker>` so future
    * re-runs can locate and update the same comment.
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

  /** Fetch comments for a PR/issue with their numeric ids. Returns an internal
    * list used only by [[upsertCommentAt]]; ids are GitHub-issued ints and
    * never leak into the public API.
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

  /** PATCH an existing issue/PR comment body via the REST API. `id` is the
    * GitHub-issued numeric comment id returned by the comments endpoint.
    */
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
        val tag = c.conclusion
          .orElse(c.state)
          .orElse(c.status)
          .getOrElse("?")
        s"${c.name.getOrElse("?")}: $tag"
      .mkString("\n")
    BuildStatus(outcome, log)

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
      // `buildStatus` already retries a transient gh/GitHub blip internally
      // ([[ghRead]]); a failure here means the read failed past that budget.
      val status = buildStatus(pr)
      val now = System.nanoTime()
      // Sticky watermark: once we've seen even one non-empty rollup, the
      // "no CI configured" hypothesis is disproven, so a later transient
      // empty rollup (API blip, retry) can't fire NoChecksConfigured.
      val seen = sawAnyCheck || status.log.nonEmpty
      if status.outcome != BuildOutcome.Pending then Right(status)
      else if !seen && now >= noChecksDeadline then
        Left(new NoChecksConfigured(noChecksGrace))
      else if now >= deadline then Left(new BuildTimedOut(timeout))
      else
        sleep(pollInterval)
        loop(seen)

    loop(sawAnyCheck = false)

  /** Run `gh` once and return the raw [[CliResult]]. The single point every gh
    * invocation funnels through. Used directly only by [[createPr]], which
    * inspects the exit code itself to split recoverable cases from failures;
    * every other call goes through [[ghRead]] or [[ghMutate]].
    */
  private def runGhResult(args: String*): CliResult =
    cli.run("gh" +: args, cwd = workDir)

  /** Run `gh` once, returning stdout or aborting on a non-zero exit. The shared
    * abort-on-failure logic behind [[ghRead]] and [[ghMutate]] — NOT called
    * directly, so the read-vs-mutate (and thus retry-vs-no-retry) choice is
    * always explicit at the call site via one of those two wrappers.
    */
  private def runGh(args: String*): String =
    val result = runGhResult(args*)
    if result.exitCode != 0 then fail(s"gh ${args.mkString(" ")}", result)
    result.stdout

  /** Abort with a uniform `"<label> failed (exit N): <stderr>"` message for an
    * unrecoverable CLI failure. Callers handle the EXPECTED non-zero exits (PR
    * already exists, no commits) as `Left`s before reaching here.
    */
  private def fail(label: String, result: CliResult): Nothing =
    throw OrcaFlowException(
      s"$label failed (exit ${result.exitCode}): ${result.stderr}"
    )

  /** Run an **idempotent read** (`api` GET, `pr view`, `pr list`), retrying a
    * transient failure under [[readRetryConfig]]. Safe to retry precisely
    * because it has no side effect.
    */
  private def ghRead(args: String*): String =
    retry(readRetryConfig)(runGh(args*))

  /** Run a **mutating** `gh` call (`pr comment`, `pr edit`, …) exactly once —
    * deliberately NOT retried: a retry after a lost response would double the
    * side effect (duplicate comment / PR edit). Use [[ghRead]] for reads. The
    * one-PR-per-branch idempotency for `pr create` is handled separately in
    * [[createPr]] (it inspects the exit code itself), which is why it doesn't
    * go through this wrapper.
    */
  private def ghMutate(args: String*): String = runGh(args*)

private[orca] object OsGitHubTool:

  /** Default retry for idempotent read-only `gh` calls (`readIssue`,
    * `read*Comments`, `buildStatus`): ride out a transient gh/GitHub failure
    * with a bounded exponential backoff before giving up. Injectable on the
    * constructor so tests can use a no-delay schedule.
    */
  val defaultReadRetry: Schedule =
    Schedule.exponentialBackoff(1.second).maxRetries(4)

  // --- Recoverable `gh pr create` stderr/stdout predicates ---
  //
  // gh has no machine-readable signal for "an open PR already exists" or "the
  // branch has no commits", so `createPr` splits these recoverable cases from a
  // system failure by matching gh's human-readable output (combined
  // stdout+stderr). gh's messages are UI text, not a contract, so the matchers
  // are centralised here — named, documented, unit-tested — and kept lenient so
  // a wording tweak doesn't reclassify a recoverable failure as fatal. Each
  // case-folds its input internally, so callers pass gh's output verbatim.

  /** True when `gh pr create` reported that an open PR already exists for the
    * head branch — the case `createPr` resolves by reusing the existing PR
    * (R24). Takes gh's combined stdout+stderr verbatim and case-folds itself.
    */
  private[tools] def isPrAlreadyExists(combined: String): Boolean =
    combined.toLowerCase.contains("already exists")

  /** True when `gh pr create` reported there is nothing to open a PR from (the
    * branch has no commits ahead of base, or hasn't been pushed yet).
    * Case-folds internally (see [[isPrAlreadyExists]]).
    */
  private[tools] def isNoCommitsToPr(combined: String): Boolean =
    val lower = combined.toLowerCase
    lower.contains("no commits") || lower.contains("must first push")

  private val StatusCompleted = "COMPLETED"
  private val SuccessfulConclusions = Set("SUCCESS", "NEUTRAL", "SKIPPED")
  private val LegacyStateSuccess = "SUCCESS"
  private val LegacyStatePending = "PENDING"

  /** Reduce a heterogeneous list of check entries to a single outcome. Empty
    * list is treated as Pending: just after a push, GitHub returns zero checks
    * for several seconds while the workflow is being registered, so collapsing
    * empty to Success would race with CI startup and surface as a false "build
    * green" before CI even ran. Callers that hit a repo with no CI configured
    * at all will see Pending until `waitForBuild`'s `noChecksGrace` window
    * elapses, at which point it converts to `NoChecksConfigured` — a more
    * actionable diagnostic than a generic "build didn't finish" timeout.
    */
  def aggregateOutcome(checks: List[GhCheck]): BuildOutcome =
    if checks.isEmpty then BuildOutcome.Pending
    else if checks.exists(isPending) then BuildOutcome.Pending
    else if checks.forall(isSuccess) then BuildOutcome.Success
    else BuildOutcome.Failure

  private def isPending(c: GhCheck): Boolean =
    c.status.exists(_ != StatusCompleted) ||
      c.state.contains(LegacyStatePending) ||
      (c.status.isEmpty && c.state.isEmpty && c.conclusion.isEmpty)

  private def isSuccess(c: GhCheck): Boolean =
    c.conclusion.exists(SuccessfulConclusions.contains) ||
      c.state.contains(LegacyStateSuccess)
