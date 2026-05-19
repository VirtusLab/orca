# Orca — Design Document

Deterministic agentic software development.

## What is Orca

Orca is a Scala library for defining and executing development workflows — planning, coding, review, fix, create PR — as composable, type-safe scripts. The actual coding, reviews, and LLM interactions are delegated to backends (Claude Code, Codex). Orca provides the orchestration: structured I/O, session management, autonomous and interactive execution, and tool integrations (with Slack and other channels on the roadmap).

```scala
//> using dep "org.virtuslab::orca:0.1"
//> using jvm 21
import orca.{*, given}

flow(OrcaArgs(args)):
  val (_, plan) = claude.resultAs[TaskPlan].interactive(userPrompt)
  git.createBranch(plan.branchName)
  // ...
```

Flows use `.sc` (scala-cli script) files, where top-level expressions are allowed. `args` is scala-cli's script argv. For `.scala` files, wrap in `@main def run(args: String*) = flow(OrcaArgs(args.toArray)) { ... }`.

```bash
scala-cli run my-flow.sc -- "implement feature X"
```

Orca requires JDK 21+ (for virtual threads).

## How it works

### Execution model

The entry point is the `orca` function — it parses CLI arguments, creates the `FlowContext`, and runs the flow. Orca uses Scala 3 **context functions**: `FlowContext` is passed as a `given`, and top-level accessors (`claude`, `codex`, `git`, `gh`, `fs`, `userPrompt`) resolve it implicitly.

All CLI arguments are optional. `userPrompt` contains the positional text argument (empty string if not provided) — some flows read their task from JIRA, a file, or other sources.

| Flag | Description |
|---|---|
| `<prompt>` | Positional (optional): task description, available as `userPrompt` |
| `--verbose` | Verbose logging |

### Input serialization

LLM tools accept any input type with an `AgentInput` instance in scope:

```scala
trait AgentInput[A]:
  def serialize(a: A): String

// Provided by the library:
given AgentInput[String]                           // pass-through
given [A: ConfiguredJsonValueCodec]: AgentInput[A] // serialize to JSON
```

Only the **output** type needs a `Schema` (for JSON Schema generation) and a codec (for deserialization). The `.resultAs[O]` builder captures the output type, then methods like `prompt`/`interactive` only need the input type:

```scala
claude.resultAs[TaskPlan].prompt(input)   // I inferred from input, O = TaskPlan
```

`ConfiguredJsonValueCodec` is jsoniter-scala's derives-compatible codec with default settings (camelCase keys). Standard collections like `List[String]` are handled automatically by jsoniter-scala.

### Modes of operation

Each stage in a flow is either **interactive** or **autonomous**:

- **Interactive** (e.g., planning, design) — the user collaborates with the agent over multiple turns via a configured interaction channel (terminal by default, pluggable). The backend drives a stream-json subprocess; `--json-schema` validates the final structured turn, and a typed `result` message on the subchannel signals completion (no in-band markers). See ADR 0006.
- **Autonomous** (e.g., coding, review) — the agent runs without user prompts (headless: non-interactive, single-shot, structured output on stdout). Live progress is forwarded to registered event listeners.

A flow freely mixes both.

### Tools

Each tool is a trait (interface) implemented by the library. Custom implementations can be substituted:

```scala
flow(OrcaArgs(), git = Some(MyGitImpl()), interaction = SlackInteraction("#dev")):
  // uses custom git implementation and Slack for interactive stages
```

All tool methods throw on failure (e.g., `git push` fails → exception). Flow authors handle errors with standard try/catch or the `fail(...)` helper.

## Architecture

### Package layout

The library is organised so `import orca.{*, given}` exposes only the names
flow scripts actually write. Everything else lives in a sub-package that
implementers and advanced callers opt into explicitly.

| Package | Contents |
|---|---|
| `orca` | Flow DSL — `flow`, `stage`, `fail`, accessors (`claude`, `codex`, `git`, `gh`, `fs`, `userPrompt`), `OrcaArgs`, `FlowContext`, `OrcaFlowException`, `OrcaInteractiveCancelled`, `OrcaDebug`. Plus a thin `exports.scala` re-exporting the user surface from sub-packages. |
| `orca.events` | Flow-wide event log: `OrcaEvent`, `OrcaListener`, `EventDispatcher`, `CostTracker`, `Usage`. |
| `orca.llm` | LLM call API + default implementations: `LlmTool` / `ClaudeTool` / `CodexTool` / `LlmCall`, `LlmConfig`, `AutoApprove`, `UnapprovedPolicy`, `BackendTag`, `SessionId`, `JsonData`, `Announce`, `AgentInput`, `Prompts`, plus the `AbstractDefaultLlmTool`, `DefaultLlmCall`, `DefaultPrompts`, `ResponseParser` defaults backend tools extend. |
| `orca.backend` | LLM SPI (implemented per backend, never named in scripts): `LlmBackend`, `LlmResult`, `Conversation`, `ConversationEvent` (+ `ApprovalDecision`), `Interaction`, `StreamConversation`. |
| `orca.tools.git`, `orca.tools.github`, `orca.tools.fs` | Tool traits (`GitTool`, `GitHubTool`, `FsTool`) co-located with their default `Os*` implementations. User-facing data types from `orca.tools.github` (`Issue`, `IssueHandle`, `PrHandle`, `Comment`, `BuildStatus`, `BuildOutcome`) are re-exported from `orca`. |
| `orca.plan`, `orca.review` | Higher-level workflow APIs and their domain types. The user-callable entry points are re-exported from `orca`. Bug-triage types live in `orca.plan` alongside `Plan`/`Task`. |
| `orca.subprocess` | CLI-runner internals: `CliRunner`, `PipedCliProcess`, `OsProcCliRunner`, `QuietProc`. |
| `orca.util` | Small leaf utilities — `JsonSchemaGen`, `TextWrap`. |
| `orca.tools.{claude, codex}` | Concrete LLM backend implementations. |

`orca/exports.scala` re-exports the user-facing subset of every sub-package
into the root `orca` namespace, so the standard `import orca.{*, given}`
continues to pick up `LlmTool`, `Plan`, `IssueHandle`, etc. without
sub-package imports. Sub-packages exist for navigability and to keep the
root namespace focused. Backend authors writing a new `LlmBackend` import
`orca.backend.*`; channel authors writing a new `Interaction` import
`orca.backend.{Conversation, ...}`; flow scripts import nothing more than
`orca.{*, given}`.

### Tool traits

```scala
trait GitTool:
  def createBranch(name: String): Unit       // git checkout -b
  def checkout(name: String): Unit           // git checkout (existing)
  def commit(message: String): Unit
  def push(): Unit
  def currentBranch(): String
  def diff(): String
  def log(n: Int = 10): List[CommitInfo]

trait GitHubTool:
  def createPr(title: String, body: String): PrHandle
  def readComments(pr: PrHandle): List[Comment]
  def writeComment(pr: PrHandle, body: String): Unit
  def buildStatus(pr: PrHandle): BuildStatus
  def waitForBuild(pr: PrHandle, timeout: Duration): BuildStatus

trait FsTool:
  def read(path: String): String
  def write(path: String, content: String): Unit
  def list(glob: String): List[String]

// Supporting types
case class PrHandle(owner: String, repo: String, number: Int)
case class BuildStatus(success: Boolean, log: String)
case class Comment(author: String, body: String)
case class CommitInfo(hash: String, message: String, author: String)
```

Git and GitHub tools are thin wrappers around `git` and `gh` CLI commands, executed via `os-lib`.

### LLM tool

Backends are accessed as named instances — `claude` and `codex` — with method chaining for model and config selection:

```scala
claude.resultAs[TaskPlan].prompt(input)          // Claude Code, default model
claude.sonnet.resultAs[TaskPlan].prompt(input)   // Claude Code, Sonnet
claude.haiku.ask("quick question")             // Haiku (untyped convenience)
codex.resultAs[TaskPlan].prompt(input)           // Codex, default model

claude.withConfig(LlmConfig(
  systemPrompt = Some("You are a performance reviewer...")
)).resultAs[ReviewResult].prompt(diff)
```

Session IDs are **type-safe** via opaque types parameterized by backend:

```scala
opaque type SessionId[B <: BackendTag] = String

enum BackendTag:
  case ClaudeCode, Codex
```

The full traits:

```scala
trait LlmTool[B <: BackendTag]:
  def resultAs[O: Schema: ConfiguredJsonValueCodec]: LlmCall[B, O]
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String
  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]

/** Model variants are backend-specific. */
trait ClaudeTool extends LlmTool[BackendTag.ClaudeCode]:
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[BackendTag.Codex]:
  def mini: CodexTool

trait LlmCall[B <: BackendTag, O]:
  def prompt[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): O
  def startSession[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): (SessionId[B], O)
  def continueSession[I: AgentInput](sessionId: SessionId[B], input: I, config: LlmConfig = LlmConfig.default): O
  def interactive[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): (SessionId[B], O)
  def continueInteractive[I: AgentInput](sessionId: SessionId[B], input: I, config: LlmConfig = LlmConfig.default): O
```

#### LLM configuration

```scala
case class LlmConfig(
  model: Option[String] = None,
  systemPrompt: Option[String] = None,
  autoApprove: AutoApprove = AutoApprove.All,
  onUnapproved: UnapprovedPolicy = UnapprovedPolicy.Deny,
  retrySchedule: Schedule = Schedule.exponentialBackoff(1.second).maxRetries(3)
)

enum AutoApprove:
  case All
  case Only(tools: Set[String])

enum UnapprovedPolicy:
  case Deny       // silently deny (autonomous stages)
  case AskUser    // prompt the user (interactive stages only)
```

Retries use Ox's `Schedule` directly. Failures that exhaust retries throw `LlmCallFailedException`.

#### Structured types

```scala
case class ReviewIssue(
  severity: Severity,
  confidence: Double,     // 0.0–1.0
  description: String,
  file: Option[String],
  line: Option[Int],
  suggestion: Option[String]
) derives Schema, ConfiguredJsonValueCodec

enum Severity derives Schema, ConfiguredJsonValueCodec:
  case Critical, Warning, Info

case class ReviewResult(
  issues: List[ReviewIssue],
  summary: String
) derives Schema, ConfiguredJsonValueCodec

object ReviewResult:
  val empty: ReviewResult = ReviewResult(Nil, "")

case class IgnoredIssue(issue: ReviewIssue, reason: String) derives Schema, ConfiguredJsonValueCodec

case class IgnoredIssues(issues: List[IgnoredIssue]) derives Schema, ConfiguredJsonValueCodec:
  def ++(other: IgnoredIssues): IgnoredIssues = IgnoredIssues(issues ++ other.issues)
  def nonEmpty: Boolean = issues.nonEmpty
  def format: String = issues.map(i => s"- [${i.issue.severity}] ${i.issue.description}: ${i.reason}").mkString("\n")

case class ReviewContext(summary: String, filesChanged: List[String]) derives Schema, ConfiguredJsonValueCodec
case class SelectedReviewers(names: List[String]) derives Schema, ConfiguredJsonValueCodec:
  def pick(all: List[LlmTool[?]]): List[LlmTool[?]] = all.filter(r => names.contains(r.name))

case class Usage(inputTokens: Long, outputTokens: Long, cost: Option[BigDecimal])

/** Pre-configured reviewer agents provided by the library. */
val defaultReviewers: List[LlmTool[?]]  // performance, readability, test-coverage, code-functionality, abstraction
```

#### Library functions

```scala
/** Generic fix loop: evaluate → fix → re-evaluate, until clean or all remaining
  * issues are deliberately ignored. Max 10 iterations by default. */
def fixLoop(
  evaluate: () => ReviewResult,
  fix: (List[ReviewIssue]) => IgnoredIssues,
  maxIterations: Int = 10
)(using FlowContext): IgnoredIssues

/** Review + fix loop with parallel reviewers and optional linter. Built on fixLoop. */
def reviewAndFix[B <: BackendTag](
  coder: LlmTool[B],
  sessionId: SessionId[B],
  reviewers: List[LlmTool[?]],
  task: String,
  lintCommand: Option[String] = None,
  confidenceThreshold: Double = 0.7
)(using FlowContext): IgnoredIssues

/** Built-in lint: run a command, summarize errors via LLM, return as ReviewResult. */
def lint(
  command: String,
  llm: LlmTool[?] = claude.haiku
)(using FlowContext): ReviewResult
```

Note: `reviewAndFix` uses a shared type parameter `B` for `coder` and `sessionId` to preserve compile-time session ID safety.

#### FlowContext

```scala
trait FlowContext:
  val claude: ClaudeTool
  val codex: CodexTool
  val git: GitTool
  val gh: GitHubTool
  val fs: FsTool
  val userPrompt: String
  def emit(event: OrcaEvent): Unit
```

#### Prompt construction

The prompt sent to the backend is assembled from a pluggable `Prompts`:

```scala
trait Prompts:
  def autonomous(input: String, outputSchema: String, config: LlmConfig): String
  def interactive(input: String, outputSchema: String, config: LlmConfig): String
```

Custom prompt builders can be provided via `flow(..., prompts = ...)`. For **headless** calls, the backend returns JSON on stdout; a parse failure triggers a corrective-retry prompt (counts against the retry budget). For **interactive** calls, the backend runs a stream-json subprocess (ADR 0006) and emits typed `ConversationEvent`s; the final `result` message carries the validated `structured_output` when `--json-schema` is supplied. Interactive parse failures surface to the caller without retry — the user is steering the session.

### Events and interaction

All agent output flows through a typed event system:

```scala
enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String, result: String)
  case LlmOutput(text: String)
  case ToolUse(tool: String, args: String)
  case TokensUsed(usage: Usage)
  case Error(message: String)

trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit
```

Events are dispatched synchronously. The library emits events automatically for every LLM call and stage transition.

**Interaction** is bidirectional — it displays output to the user and receives commands during interactive stages. The backend produces a live `Conversation[B]`; the `Interaction` drives it by consuming `ConversationEvent`s and responding to `ApproveTool` prompts:

```scala
trait Interaction:
  def listeners: List[OrcaListener]
  def drive[B <: BackendTag](conversation: Conversation[B]): LlmResult[B]
```

Built-in: `TerminalInteraction` (default, JLine 3 + fansi), `SlackInteraction(channel)`. Additional listeners for telemetry:

```scala
flow(OrcaArgs(), interaction = TerminalInteraction, extraListeners = List(CostTracker)):
  // ...
```

Helper functions emit events internally:

```scala
def stage[T](name: String)(body: => T)(using FlowContext): T
def fail(message: String)(using FlowContext): Nothing  // emits Error, throws OrcaFlowException
```

### Backend abstraction

```scala
trait LlmBackend[B <: BackendTag]:
  def runHeadless(prompt: String, config: LlmConfig, workDir: Path): LlmResult[B]
  def continueHeadless(sessionId: SessionId[B], prompt: String, config: LlmConfig, workDir: Path): LlmResult[B]
  def runInteractive(prompt: String, config: LlmConfig, workDir: Path, outputSchema: Option[String]): Conversation[B]
  def continueInteractive(sessionId: SessionId[B], prompt: String, config: LlmConfig, workDir: Path, outputSchema: Option[String]): Conversation[B]

case class LlmResult[B <: BackendTag](sessionId: SessionId[B], output: String, usage: Usage)

trait Conversation[B <: BackendTag]:
  def events: Iterator[ConversationEvent]
  def awaitResult(): LlmResult[B]
  def sendUserMessage(text: String): Unit
  def cancel(): Unit
```

`(using Ox)` provides a structured concurrency scope from the Ox library for lifecycle management.

## Technical decisions

### Backend: Claude Code

**Headless**: `claude -p "<prompt>" --output-format json --append-system-prompt-file <path> --resume <session_id>`. Returns JSON with `session_id`, `result`, `usage`. Streaming via `--output-format stream-json` (NDJSON).

**Interactive**: `claude --print --input-format stream-json --output-format stream-json --verbose --include-partial-messages --json-schema <inline>`. The backend writes the initial user turn as NDJSON on stdin, reads typed messages (`system init`, `assistant`, `user`, `result`, `control_request`, `stream_event`) from stdout, and translates them to `ConversationEvent`s for the channel. Completion is the `result` message; no in-band markers. See ADR 0006.

### Backend: Codex (OpenAI)

**Headless**: `codex exec --json --full-auto` — JSONL event stream on stdout. Session ID from `thread.started` event.

**Interactive**: app-server architecture (`codex app-server --listen ws://...`). JSON-RPC over WebSocket: `thread/start`, `turn/start`, `thread/resume`. User attaches TUI via `codex --remote`. Completion is signalled by a terminal event on the RPC stream, translated to `ConversationEvent.Result` by the Codex backend.

**App-server lifecycle**: lazily spawned on first call, reused across the flow (each call = separate thread), shut down via Ox `useInScope`. Auto-restart on crash.

**Key differences**:

| Concern | Claude Code | Codex |
|---|---|---|
| Headless | `claude -p` | `codex exec --json` |
| Structured output | `--output-format json` | `--json` + `--output-schema` |
| System prompt | `--append-system-prompt-file` | `AGENTS.md` or `--config developer_instructions` |
| Session ID | `--session-id <UUID>` (pre-assigned) | `thread.started` event / `thread/start` RPC |
| Interactive | Stream-json stdio subprocess (ADR 0006) | App-server (WebSocket) + TUI attachment |
| Completion signal | `result` message on stream-json subchannel | Terminal RPC event |

### Dependency stack

| Library | Purpose |
|---|---|
| **Scala 3.x LTS** | Context functions, opaque types, enums, derives |
| **mainargs** | CLI argument parsing (internal) |
| **sttp-client** | Codex app-server JSON-RPC over WebSocket (Ox backend) |
| **JLine 3** | Terminal control, line editing, approval prompts |
| **fansi** | Styled terminal output |
| **os-lib** | Subprocess management |
| **jsoniter-scala** | JSON codec (`derives ConfiguredJsonValueCodec`) |
| **tapir (apispec-docs)** | JSON Schema generation from `Schema` |
| **Ox** | Structured concurrency, `par`, `retry`, `Schedule` |
| **scribe** | Async logging |

### Concurrency

Ox structured concurrency with virtual threads. Reviewer agents run concurrently via `ox.par`. Each reviewer runs in an isolated temp workdir so concurrent claude subprocesses don't contend. For Codex, concurrent reviewers share the app-server but use separate threads.

### Local development

```bash
# Terminal 1: continuous local publish
cd orca && sbt ~publishLocal

# Terminal 2: run the script
scala-cli run --ttl 0 my-flow.sc
```

Script: `//> using repository ivy2Local` + `//> using dep org.virtuslab::orca:0.1-SNAPSHOT`. `--ttl 0` defeats Coursier's cache. Production: Maven Central.

## Example flow

Full development lifecycle — plan, code, lint, review, PR, CI fix loop, ignored issues:

```scala
//> using dep "org.virtuslab::orca:0.1"
//> using jvm 21
import orca.{*, given}

case class TaskPlan(tasks: List[Task], generalPrompt: String) derives JsonData
case class Task(description: String, acceptanceCriteria: String) derives JsonData
case class CodingResult(success: Boolean, message: String) derives JsonData

flow:
  // 1. Plan interactively
  val (sessionId, taskPlan) = stage("Planning"):
    claude.resultAs[TaskPlan].interactive(userPrompt)

  git.createBranch(claude.haiku.ask(s"Branch name for: $userPrompt"))

  // 2. Code each task, review, fix, commit
  var allIgnored = IgnoredIssues(Nil)

  for task <- taskPlan.tasks do
    val result = stage(s"Coding: ${task.description}"):
      claude.resultAs[CodingResult].continueSession(
        sessionId, task.description,
        LlmConfig(autoApprove = AutoApprove.All)
      )
    if !result.success then
      fail(s"Coding failed: ${result.message}")

    // LLM selects relevant reviewers
    val reviewContext = claude.resultAs[ReviewContext].prompt(
      s"Summarize changes for: ${task.description}\n${git.diff()}"
    )
    val selected = claude.resultAs[SelectedReviewers].prompt(
      s"Which reviewers are relevant?\n${defaultReviewers.map(_.name)}\n${reviewContext.summary}"
    )

    // Lint + review + fix loop
    val ignored = reviewAndFix(
      coder = claude, sessionId = sessionId,
      reviewers = selected.pick(defaultReviewers),
      task = task.description,
      lintCommand = Some("scalac -Xlint .")
    )
    allIgnored = allIgnored ++ ignored
    git.commit(s"feat: ${task.description}")

  // 3. Create PR, comment ignored issues
  git.push()
  val pr = gh.createPr(title = s"feat: $userPrompt", body = taskPlan.generalPrompt)

  if allIgnored.nonEmpty then
    gh.writeComment(pr, s"Deliberately ignored issues:\n${allIgnored.format}")

  // 4. Wait for CI, fix build failures
  val buildIgnored = fixLoop(
    evaluate = () =>
      val status = gh.waitForBuild(pr, timeout = 30.minutes)
      if status.success then ReviewResult.empty
      else claude.resultAs[ReviewResult].prompt(s"Summarize build failures:\n${status.log}"),
    fix = issues =>
      claude.resultAs[IgnoredIssues].continueSession(sessionId, issues)
  )

  if buildIgnored.nonEmpty then
    gh.writeComment(pr, s"Ignored build issues:\n${buildIgnored.format}")
```

## Roadmap

### Medium-term

- **Progress tracker**: checkpoint/restore component that observes `StageCompleted` events and skips already-completed stages on restart. Checkpoints stored as git tags or metadata files.
- **Task tracking**: create GitHub Issues / Linear tasks from plans.
- **Sonar integration**: feed static analysis results into the review loop.
- **Slack interaction**: substitute terminal for Slack in interactive stages.

### Long-term

- **Sandbox / remote execution**: run agent sessions in Docker/Firecracker or on a remote machine.
- **Devflow generation**: describe a workflow in natural language, generate the `.sc` script.
- **Knowledge graph**: structured project context for agents to query.
