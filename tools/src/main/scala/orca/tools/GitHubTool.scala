package orca.tools

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.{OrcaFlowException, WorkspaceWrite}
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{CliResult, CliRunner}
import orca.util.TextUtil
import ox.sleep
import ox.resilience.{ResultPolicy, RetryConfig, retry}
import ox.scheduling.Schedule

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

case class IssueHandle(owner: String, repo: String, number: Int):
  /** Canonical GitHub short-form `<owner>/<repo>#<number>`. */
  def shortRef: String = s"$owner/$repo#$number"

object IssueHandle:
  // Owner and repo are restricted to GitHub's own name charsets rather than
  // "anything but a separator": both are spliced into `gh api` request paths,
  // so `?`, `#` and `..` must not survive parsing.
  private[tools] val Owner = """[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"""
  // Dots are legal in repo names (`foo.github.io`), but a segment of *only*
  // dots is not a repo — it's `.`/`..` path traversal, so require at least one
  // non-dot character. Anchor-free, since this is spliced mid-pattern.
  private[tools] val Repo = """[A-Za-z0-9._-]*[A-Za-z0-9_-][A-Za-z0-9._-]*"""

  private val ShortRefPattern =
    s"""\\s*($Owner)/($Repo)#(\\d+)\\s*""".r

  /** Bare browser URL for an issue or a PR — no trailing path segment, query
    * string or fragment, and a lowercase host. The `pull` alternative matters
    * because PR refs are parsed through `IssueHandle` too (GitHub numbers
    * issues and PRs from one sequence).
    */
  private val UrlPattern =
    s"""\\s*(?:https?://)?(?:www\\.)?github\\.com/($Owner)/($Repo)/(?:issues|pull)/(\\d+)/?\\s*""".r

  /** Matches a digit string that fits in an `Int`, so an out-of-range issue
    * number falls through to the malformed-input branch instead of throwing.
    */
  private object IssueNumber:
    def unapply(s: String): Option[Int] = s.toIntOption

  /** Parse the canonical `<owner>/<repo>#<number>` short-form, or a GitHub
    * browser URL — `https://github.com/<owner>/<repo>/issues/<number>` and the
    * `/pull/` equivalent (scheme, `www.` and trailing slash all optional).
    * Surrounding whitespace is tolerated in either form.
    */
  def parse(s: String): Either[String, IssueHandle] =
    s match
      case ShortRefPattern(owner, repo, IssueNumber(number)) =>
        Right(IssueHandle(owner, repo, number))
      case UrlPattern(owner, repo, IssueNumber(number)) =>
        Right(IssueHandle(owner, repo, number))
      case _ =>
        Left(
          s"expected '<owner>/<repo>#<number>' or " +
            s"'https://github.com/<owner>/<repo>/{issues,pull}/<number>', got: '$s'"
        )

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
      "no commits between the base branch and the pushed branch — nothing to " +
        "open a pull request from"
    )

/** The head branch is not on the remote — e.g. a resumed flow that skipped its
  * already-recorded push stage. Distinct from [[NoCommitsToPr]], where the
  * branch is pushed but carries nothing on top of base.
  */
final class BranchNotPushed
    extends PrCreateFailed(
      "the head branch is not on the remote — push the branch first (fork " +
        "clones are unsupported)"
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
  /** Read-only probe: can a PR be opened from this checkout, and where to.
    * Asks git for the `origin` remote and gh for a credential and the
    * repository it resolves, writing nothing — so a flow can branch on the
    * answer before committing to a PR-opening stage. Only a passing network
    * failure is waited out; no remote, no credential and no such repository
    * are answered at once.
    */
  def availability(): GitHubAvailability

  /** Open a PR from the current branch as it exists on the remote: the branch
    * must already be pushed to the repo the PR targets (fork clones are
    * unsupported), and commits made locally after the last push are not
    * included. Refusals with a recovery path come back as `Left`s; throws on
    * detached HEAD, on gh output carrying no PR URL, and on system failures
    * (auth, network).
    */
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

  /** [[readRetryConfig]] for the probe's `gh repo view`, which keeps the raw
    * result: same schedule, retrying a non-zero exit only while
    * [[classifyFailure]] reads it as [[GhFailure.Transient]]. A gh that cannot
    * start is not retried, as in [[readRetryConfig]].
    */
  private val probeRetryConfig: RetryConfig[Throwable, CliResult] =
    RetryConfig(
      readRetry,
      ResultPolicy(
        isSuccess = r =>
          r.exitCode == 0 || (classifyFailure(r) match
            case GhFailure.Hard      => true
            case GhFailure.Transient => false
          ),
        isWorthRetrying = _ => false
      )
    )

  def availability(): GitHubAvailability =
    import GitHubAvailability.{Available, Unavailable}
    import GitHubUnavailable.*
    originUrl() match
      case OriginProbe.GitUnusable(reason) => Unavailable(GitUnusable(reason))
      case OriginProbe.NoOrigin            => Unavailable(NoRemote)
      case OriginProbe.Origin(url) =>
        OsGitTool.remoteHost(url) match
          case None => Unavailable(NoHost(url))
          case Some(host) =>
            credential(host) match
              case CredentialProbe.Present => repoGhResolves(host)
              // A gh that will not start says nothing about the host, so it
              // cannot be evidence that the host isn't GitHub.
              case CredentialProbe.GhUnusable(reason) =>
                Unavailable(Unreachable(host, reason))
              // gh ran and holds no credential for the host: on github.com
              // that is the user's auth, anywhere else it is how a non-GitHub
              // host presents, since gh only logs in to GitHub.
              case CredentialProbe.Absent(reason) =>
                if host == GitHubDotCom then
                  Unavailable(Unreachable(host, reason))
                else Unavailable(NotGitHub(host))

  /** The `origin` remote's URL as git resolves it — `url.<base>.insteadOf`
    * applied — or [[OriginProbe.NoOrigin]] when there is no such remote: `git
    * ls-remote --get-url` then echoes the name back and exits 0. A git that
    * will not run at all is reported rather than aborting the flow.
    */
  private def originUrl(): OriginProbe =
    try
      val result = cli.run(
        Seq("git", "ls-remote", "--get-url", "origin"),
        env = OsGitTool.nonInteractiveEnv,
        cwd = workDir
      )
      Option
        .when(result.exitCode == 0)(result.stdout.trim)
        .filter(url => url.nonEmpty && url != "origin")
        .fold(OriginProbe.NoOrigin)(OriginProbe.Origin(_))
    catch
      case NonFatal(e) =>
        OriginProbe.GitUnusable(
          TextUtil.throwableMessage(e, firstLineOnly = true)
        )

  /** What `gh auth token --hostname <host>` answered — a local config read,
    * never retried.
    */
  private def credential(host: String): CredentialProbe =
    try
      val result = runGhResult("auth", "token", "--hostname", host)
      if result.exitCode == 0 then CredentialProbe.Present
      else
        CredentialProbe.Absent(
          ghReason(
            result,
            s"gh holds no credential for $host (exit ${result.exitCode})" +
              s" — run `gh auth login --hostname $host`"
          )
        )
    catch case NonFatal(e) => CredentialProbe.GhUnusable(cannotRunGh(e))

  /** The repository gh resolves from this checkout — the fork parent or the `gh
    * repo set-default` choice where those apply, i.e. what `gh pr create`
    * would target. Host, owner and repo all come out of the one `url` gh
    * reports, so the three name a single repository; `gitHost` only labels the
    * failures, which have no gh answer to take a host from.
    */
  private def repoGhResolves(gitHost: String): GitHubAvailability =
    def unreachable(reason: String): GitHubAvailability =
      GitHubAvailability.Unavailable(
        GitHubUnavailable.Unreachable(gitHost, reason)
      )
    tryRepoView() match
      case Left(reason) => unreachable(reason)
      case Right(view) if view.exitCode != 0 =>
        unreachable(
          ghReason(
            view,
            s"gh repo view failed (exit ${view.exitCode}) — run `gh repo view" +
              " --json url` in this checkout to see why"
          )
        )
      case Right(view) =>
        repoFromView(view.stdout) match
          case Right(available) => available
          case Left(reason)     => unreachable(reason)

  /** Read `gh repo view --json url` output. Decoding sits behind the `Either`
    * so a payload gh never documented is an answer from the probe rather than
    * an exception out of it.
    */
  private def repoFromView(stdout: String): Either[String, GitHubAvailability] =
    try
      val url = readFromString[GhRepoViewJson](stdout).url
      url.stripSuffix("/").stripSuffix(".git") match
        case RepoUrlPattern(host, owner, repo) =>
          Right(
            GitHubAvailability.Available(
              host = host,
              owner = owner,
              repo = repo
            )
          )
        case _ =>
          Left(
            s"gh named the repository '$url', which is not " +
              "https://<host>/<owner>/<repo>"
          )
    catch
      case NonFatal(e) =>
        Left(
          "could not read `gh repo view` output " +
            s"(${TextUtil.throwableMessage(e, firstLineOnly = true)}) — run " +
            "`gh repo view --json url` in this checkout to see what gh printed"
        )

  /** `gh repo view --json url` under [[probeRetryConfig]]. A gh that could not
    * be started comes back as a `Left` reason — the probe answers rather than
    * aborting the flow.
    */
  private def tryRepoView(): Either[String, CliResult] =
    try
      Right(
        retry(probeRetryConfig)(runGhResult("repo", "view", "--json", "url"))
      )
    catch case NonFatal(e) => Left(cannotRunGh(e))

  def createPr(title: String, body: String)(using
      WorkspaceWrite
  ): Either[PrCreateFailed, PrHandle] =
    // Inspect exit code + stderr ourselves to split the recoverable failures
    // (the `PrCreateFailed` cases) from genuine system failures, so this uses
    // `runGhResult` (raw result) rather than `ghRead`/`ghMutate` (which abort
    // on non-zero exit).
    //
    // `--head` is passed explicitly: gh's own detection requires a remote ref
    // whose hash equals local HEAD's, so a stage that pushes and then commits
    // its progress log would be misread as "branch not pushed". Bare branch
    // name, no `owner:` prefix, matching `findOpenPr`.
    val head = headBranchForPr()
    val result = runGhResult(
      "pr",
      "create",
      "--title",
      title,
      "--body",
      body,
      "--head",
      head
    )
    if result.exitCode == 0 then
      val output = result.stdout.trim
      PrHandle.fromUrl(output) match
        case Some(pr) =>
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
        findOpenPr(head) match
          case Some(pr) =>
            events.onEvent(OrcaEvent.Step(s"Reusing existing PR: ${pr.url}"))
            Right(pr)
          case None => Left(new PrAlreadyExists)
      else if OsGitHubTool.isBranchNotPushed(combined) then
        Left(new BranchNotPushed)
      else if OsGitHubTool.isNoCommitsToPr(combined) then
        Left(new NoCommitsToPr)
      else fail("gh pr create", result)

  /** The current branch name for `--head`, via `git rev-parse --abbrev-ref
    * HEAD` (carrying [[OsGitTool.nonInteractiveEnv]] so an ssh/credential
    * prompt can't hang a flow). Throws on detached HEAD (rev-parse yields the
    * literal `HEAD`) and on a blank name, which would silently re-enable gh's
    * own head detection.
    */
  private def headBranchForPr(): String =
    val result = cli.run(
      Seq("git", "rev-parse", "--abbrev-ref", "HEAD"),
      env = OsGitTool.nonInteractiveEnv,
      cwd = workDir
    )
    if result.exitCode != 0 then fail("git rev-parse", result)
    val head = result.stdout.trim
    if head.isEmpty || head == "HEAD" then
      throw OrcaFlowException(
        s"cannot open a PR: not on a branch (git rev-parse gave '$head')"
      )
    head

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
    readFromString[List[GhPrListJson]](output).headOption
      .flatMap(entry => PrHandle.fromUrl(entry.url))

  /** gh's fully qualified `HOST/OWNER/REPO` `--repo` form. */
  private def repoRef(pr: PrHandle): String =
    s"${pr.host}/${pr.owner}/${pr.repo}"

  def readIssue(issue: IssueHandle): Issue =
    val target = GhTarget(issue)
    val output = ghReadApi(target.host, target.issuePath)
    val parsed = readFromString[GhIssueJson](output)
    Issue(
      title = parsed.title,
      body = parsed.body.getOrElse(""),
      author = parsed.user.login,
      state = parsed.state
    )

  def readIssueComments(issue: IssueHandle): List[Comment] =
    readCommentsAt(GhTarget(issue))

  def readPrComments(pr: PrHandle): List[Comment] =
    // The `/issues/{n}/comments` endpoint returns conversation comments for
    // both issues and PRs. Line-level review comments live at
    // `/pulls/{n}/comments` and aren't covered here.
    readCommentsAt(GhTarget(pr))

  private def readCommentsAt(target: GhTarget): List[Comment] =
    val output = ghReadApi(target.host, "--paginate", target.commentsPath)
    readFromString[List[GhCommentJson]](output).map: c =>
      Comment(author = c.user.login, body = c.body)

  def updatePr(pr: PrHandle, title: String, body: String)(using
      WorkspaceWrite
  ): Unit =
    // Use the REST API directly rather than `gh pr edit`: the latter runs a
    // GraphQL query selecting `projectCards`, which fails on repos where GitHub
    // has sunset Projects (classic). The REST PATCH endpoint doesn't touch
    // projects.
    val _ = ghMutateApi(
      Some(pr.host),
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
      repoRef(pr),
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
    upsertCommentAt(GhTarget(pr), marker, body):
      writeComment(pr, _)

  def upsertComment(issue: IssueHandle, marker: String, body: String)(using
      WorkspaceWrite
  ): Unit =
    upsertCommentAt(GhTarget(issue), marker, body):
      writeComment(issue, _)

  /** Shared upsert logic for both PR and issue targets. PATCHes the first
    * comment containing `marker`, else delegates to `createFn`. The stored body
    * is `<body>\n\n<marker>` so future re-runs can locate the same comment.
    */
  private def upsertCommentAt(
      target: GhTarget,
      marker: String,
      body: String
  )(createFn: String => Unit): Unit =
    val markedBody = s"$body\n\n$marker"
    fetchIdentifiedComments(target).find(_.body.contains(marker)) match
      case Some(existing) =>
        patchComment(target, existing.id, markedBody)
      case None =>
        createFn(markedBody)

  /** Fetch comments for a PR/issue with their GitHub-issued numeric ids, for
    * [[upsertCommentAt]]. The ids never leak into the public API.
    */
  private def fetchIdentifiedComments(
      target: GhTarget
  ): List[GhIdentifiedCommentJson] =
    val output = ghReadApi(target.host, "--paginate", target.commentsPath)
    readFromString[List[GhIdentifiedCommentJson]](output)

  /** PATCH an existing issue/PR comment body via the REST API. */
  private def patchComment(target: GhTarget, id: Long, body: String): Unit =
    val _ = ghMutateApi(
      target.host,
      "-X",
      "PATCH",
      s"repos/${target.owner}/${target.repo}/issues/comments/$id",
      "-f",
      s"body=$body"
    )

  def buildStatus(pr: PrHandle): BuildStatus =
    val output = ghRead(
      "pr",
      "view",
      pr.number.toString,
      "--repo",
      repoRef(pr),
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
    * handle the expected non-zero exits (PR already exists, branch not pushed,
    * no commits) as `Left`s before reaching here.
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

  /** [[ghRead]] against a `gh api` endpoint on `host`. */
  private def ghReadApi(host: Option[String], args: String*): String =
    ghRead(apiArgs(host, args)*)

  /** [[ghMutate]] against a `gh api` endpoint on `host`. */
  private def ghMutateApi(host: Option[String], args: String*): String =
    ghMutate(apiArgs(host, args)*)

  /** Arguments for a `gh api` call. `None` leaves the host to gh's own
    * resolution: `GH_HOST`, else the authenticated host.
    */
  private def apiArgs(host: Option[String], args: Seq[String]): Seq[String] =
    "api" +: host.fold(args)(h => "--hostname" +: h +: args)

private[orca] object OsGitHubTool:

  /** The `gh api` coordinates of one issue or PR: which host to name (`None`
    * leaves it to gh, since an [[IssueHandle]] carries none) and the
    * `repos/<owner>/<repo>/issues/<n>` the comment endpoints share. Built from
    * a handle, so a host can never be paired with another handle's owner/repo.
    */
  private case class GhTarget(
      host: Option[String],
      owner: String,
      repo: String,
      number: Int
  ):
    def issuePath: String = s"repos/$owner/$repo/issues/$number"
    def commentsPath: String = s"$issuePath/comments"

  private object GhTarget:
    def apply(pr: PrHandle): GhTarget =
      GhTarget(Some(pr.host), pr.owner, pr.repo, pr.number)
    def apply(issue: IssueHandle): GhTarget =
      GhTarget(None, issue.owner, issue.repo, issue.number)

  /** The one host that is GitHub whether or not gh can log in to it. */
  private val GitHubDotCom = "github.com"

  /** What git said about the `origin` remote. [[NoOrigin]] and [[GitUnusable]]
    * are kept apart because only the first is an answer about the checkout: a
    * git that could not be run says nothing about its remotes.
    */
  private enum OriginProbe:
    case Origin(url: String)
    case NoOrigin
    case GitUnusable(reason: String)

  /** What `gh auth token --hostname <host>` answered. [[Absent]] and
    * [[GhUnusable]] are kept apart because only the first says anything about
    * the host: gh refusing to start is the same answer on every host.
    */
  private enum CredentialProbe:
    case Present
    case Absent(reason: String)
    case GhUnusable(reason: String)

  /** Host, owner and repo of a `https://<host>/<owner>/<repo>` URL — the shape
    * of `gh repo view --json url` output, from which
    * [[OsGitHubTool.availability]] takes the repository gh resolved. A port is
    * refused, as [[PrHandle.fromUrl]] refuses it when the PR is opened: gh's
    * `--hostname` takes none.
    */
  private val RepoUrlPattern =
    s"""^https://([A-Za-z0-9.-]+)/(${IssueHandle.Owner})/(${IssueHandle.Repo})$$""".r

  /** How a failed `gh` call should be treated: retried, or answered at once. */
  private[tools] enum GhFailure:
    /** The network or GitHub itself was not available for the moment. */
    case Transient

    /** gh's answer will not change on a retry — no permission, no such
      * repository, or anything the probe does not recognise as transient.
      */
    case Hard

  /** Read a non-zero `gh` exit as [[GhFailure.Transient]] only on the
    * signatures gh's HTTP client and GitHub give for a passing outage: a
    * connection that could not be made or was cut, a name that would not
    * resolve, a timeout, a TLS failure, HTTP 5xx, or rate limiting (429). Both
    * streams are read, since gh can fail with nothing on stderr. Everything
    * else, HTTP 401/403/404 included, is [[GhFailure.Hard]].
    */
  private[tools] def classifyFailure(result: CliResult): GhFailure =
    val output = result.stderr + "\n" + result.stdout
    if TransientSignature.findFirstIn(output).isDefined then GhFailure.Transient
    else GhFailure.Hard

  // `timeout`, `tls` and `EOF` are matched in the forms Go's net/http and gh
  // print them, not bare: a repository name can contain the word.
  private val TransientSignature =
    """(?i)dial tcp|error connecting to|check your internet connection|connection (?:refused|reset)|no such host|i/o timeout|timeout exceeded|Client\.Timeout|handshake timeout|timed out|tls:|TLS handshake|(?-i:unexpected EOF|: EOF)|rate limit|HTTP (?:5\d\d|429)""".r

  /** Reason for a `gh` that could not be started. Names the likely cause
    * without asserting it: a missing binary is the common one, but an unusable
    * working directory or a non-executable `gh` throws the same way.
    */
  private def cannotRunGh(e: Throwable): String =
    s"could not run gh (${TextUtil.throwableMessage(e, firstLineOnly = true)})" +
      " — if it is not installed, get it from https://cli.github.com, then " +
      "run `gh auth login`"

  /** A user-facing reason for a `gh` call that exited non-zero: its stderr,
    * else its stdout — `gh auth status` reports there and can fail silently —
    * else `fallback`, so the reason is never blank. Collapsed onto one line,
    * since it is spliced into a single `Step`.
    */
  private def ghReason(result: CliResult, fallback: String): String =
    List(result.stderr, result.stdout)
      .map(out => TextUtil.collapseWhitespace(out.trim))
      .find(_.nonEmpty)
      .getOrElse(fallback)

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

  /** True when `gh pr create` reported no commits between the base and head
    * branches — the branch is pushed but carries nothing on top of base.
    * Excludes the missing-head-branch validation error, whose text also says
    * "no commits", so the two predicates stay disjoint.
    */
  private[tools] def isNoCommitsToPr(combined: String): Boolean =
    combined.toLowerCase.contains("no commits") && !isBranchNotPushed(combined)

  /** True when `gh pr create` failed because the head branch is not on the
    * remote: GitHub's createPullRequest validation error for a `--head` naming
    * a branch it doesn't have. Both fragments come from the same error — either
    * suffices; two are kept as insurance against upstream rewording.
    */
  private[tools] def isBranchNotPushed(combined: String): Boolean =
    val lower = combined.toLowerCase
    lower.contains("head ref must be a branch") ||
    lower.contains("head sha can't be blank")

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
