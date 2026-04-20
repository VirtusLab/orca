# Orca — Design Document

Deterministic agentic software development.

## What is Orca

Orca is a Scala library for defining and executing development workflows — planning, coding, review, fix, create PR — as composable, type-safe scripts. The actual coding, reviews, and LLM interactions are delegated to backends (Claude Code, Codex). Orca provides the orchestration: structured I/O, session management, autonomous and interactive execution, and tool integrations (with Slack and other channels on the roadmap).

```scala
//> using dep "com.virtuslab::orca:0.1"
//> using jvm 21
import orca.*

orca:
  val plan = claude.result[TaskPlan].interactive(userPrompt)
  git.createBranch(plan.branchName)
  // ...
```

Flows use `.sc` (scala-cli script) files, where top-level expressions are allowed. For `.scala` files, wrap in `@main def run() = orca: ...`.

```bash
scala-cli run my-flow.sc -- "implement feature X"
```

Orca requires JDK 21+ (for virtual threads).

## How it works

### Execution model

Users write `.sc` scripts with a single dependency on Orca and run them via `scala-cli`. The entry point is the `orca` function, which parses CLI arguments, creates the `FlowContext` with all tools, and runs the user's flow. Orca uses Scala 3 **context functions** — `FlowContext` is passed as a `given`, and top-level accessor functions (`claude`, `codex`, `git`, `gh`, `fs`, `userPrompt`) resolve it implicitly:

```scala
orca:
  // claude, codex, git, gh, fs, userPrompt are in scope
  val plan = claude.result[TaskPlan].interactive(userPrompt)
```

All CLI arguments are optional. `userPrompt` contains the positional text argument (empty string if not provided) — some flows read their task from JIRA, a file, or other sources instead.

| Flag | Description |
|---|---|
| `<prompt>` | Positional (optional): task description, available as `userPrompt` |
| `--verbose` | Verbose logging |

The default backend is Claude Code. If the script has compilation errors, scala-cli surfaces standard compiler diagnostics.

### Input serialization

The LLM tools accept any input type for which an `AgentInput` typeclass instance is in scope. The library provides default instances:

```scala
trait AgentInput[A]:
  def serialize(a: A): String

// Provided by the library:
given AgentInput[String]                           // pass-through
given [A: ConfiguredJsonValueCodec]: AgentInput[A] // serialize to JSON
```

This means: strings are passed as-is, case classes with a JSON codec are serialized to JSON. Only the **output** type needs a `Schema` (for JSON Schema generation) and a codec (for deserialization). This eliminates the need for String overloads — a single method signature handles both:

```scala
// result[O] captures the output type, then prompt/interactive/etc. only need the input type:
def result[O: Schema: ConfiguredJsonValueCodec]: LlmCall[B, O]
def prompt[I: AgentInput](input: I): O   // on LlmCall
```

### Modes of operation

Each stage in a flow is either **interactive** or **autonomous**:

- **Interactive** (e.g., planning, design) — the user collaborates with the agent over multiple turns via a configured interaction channel (terminal by default, but pluggable — see Events below). When the agent completes the stage, it emits a `<<<ORCA_DONE>>>` marker followed by structured JSON output (see Prompt construction below).
- **Autonomous** (e.g., coding, review) — the agent works without user prompts. Live progress (streaming output, status updates) is forwarded to registered event listeners. When the agent stops, the flow progresses to the next stage.

A flow freely mixes both — e.g., gather requirements interactively, code and review autonomously, then interactively confirm the PR.

### Tools

Each tool is defined as a trait (interface) and implemented by the library. Implementations wrap external capabilities and are provided to the flow as instances via `FlowContext`. Custom implementations can be substituted via the `orca` entry point:

```scala
orca(git = MyGitImpl(), interaction = SlackInteraction("#dev")):
  // uses custom git implementation and Slack for interactive stages
```

**Initial tools:**

| Tool | What it wraps | Key methods |
|---|---|---|
| `GitTool` | `git` CLI | `createBranch`, `checkout`, `commit`, `push`, `diff`, `log` |
| `GitHubTool` | `gh` CLI | `createPr`, `readComments`, `writeComment`, `buildStatus` |
| `LlmTool` | Claude Code / Codex | `prompt`, `startSession`, `continueSession`, `interactive`, `ask` |
| `FsTool` | Local filesystem | `read`, `write`, `list` |

## Architecture

### Tool traits

```scala
trait GitTool:
  def createBranch(name: String): Unit       // git checkout -b
  def checkout(name: String): Unit           // git checkout (existing)
  def commit(message: String): Unit
  def push(): Unit
  def currentBranch: String
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
```

Git and GitHub tools are thin wrappers around `git` and `gh` CLI commands, executed via `os-lib`.

### LLM tool

The LLM tool is the core abstraction for coding agent interaction. Backends are accessed as named instances — `claude` and `codex` — with method chaining for model and config selection:

```scala
// Pre-configured accessors (top-level, resolve FlowContext implicitly)
claude.result[TaskPlan].prompt(input)          // Claude Code, default model
claude.sonnet.result[TaskPlan].prompt(input)   // Claude Code, Sonnet
claude.haiku.ask("quick question")             // Claude Code, Haiku (untyped)
codex.result[TaskPlan].prompt(input)           // Codex, default model

// Custom config
claude.withConfig(LlmConfig(
  systemPrompt = Some("You are a performance reviewer...")
)).result[ReviewResult].prompt(diff)
```

The `.result[O]` builder separates the output type from the method call — making the API read naturally and keeping each method focused on one concern.

Session IDs are **type-safe** — enforced via opaque types parameterized by backend:

```scala
opaque type SessionId[B <: Backend] = String

enum Backend:
  case ClaudeCode, Codex
```

A `SessionId[Backend.ClaudeCode]` cannot be passed to a Codex method. This is checked at compile time.

The full `LlmTool` trait:

```scala
trait LlmTool[B <: Backend]:
  /** Specify the expected output type — returns a builder with prompt/interactive/session methods. */
  def result[O: Schema: ConfiguredJsonValueCodec]: LlmCall[B, O]

  /** Convenience: untyped question → text answer. */
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String

  /** Override config for this instance. */
  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]  // shorthand

/** Model variants are backend-specific. `claude` has type `ClaudeTool`, not just `LlmTool`. */
trait ClaudeTool extends LlmTool[Backend.ClaudeCode]:
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[Backend.Codex]:
  def mini: CodexTool    // codex-specific model variants
  // ...add as Codex evolves

trait LlmCall[B <: Backend, O]:
  /** One-shot prompt, no session retained. */
  def prompt[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): O

  /** Start a new session. */
  def startSession[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): (SessionId[B], O)

  /** Continue a session. */
  def continueSession[I: AgentInput](sessionId: SessionId[B], input: I, config: LlmConfig = LlmConfig.default): O

  /** Interactive session — hands the interaction channel to the user. */
  def interactive[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): (SessionId[B], O)

  /** Resume an interactive session. */
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

/** Which tools the agent may use without asking. */
enum AutoApprove:
  case All                              // approve all tool uses
  case Only(tools: Set[String])         // only these tools

/** What happens when the agent tries a non-approved tool. */
enum UnapprovedPolicy:
  case Deny                             // silently deny (autonomous stages)
  case AskUser                          // prompt the user (interactive stages only)
```

Retries use Ox's `Schedule` directly — `Schedule.exponentialBackoff(1.second).maxRetries(3)` is the default. Failures that exhaust retries throw `LlmCallFailedException`.

**Reviewer agents** are `LlmTool` instances with a review-focused system prompt. The flow can also include a step where the LLM selects which reviewers are appropriate for the changes:

```scala
val perfReviewer = claude.sonnet.withSystemPrompt("Review for performance issues...")
```

#### Review result structure

Reviewers return structured findings — each issue includes a confidence score so the flow can filter:

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
```

#### Library functions

Common flow patterns are provided as library functions:

```scala
/** Generic fix loop: evaluate → fix → re-evaluate, until clean or all remaining issues are
  * deliberately ignored. Returns the ignored issues (with explanations). */
def fixLoop(
  evaluate: () => ReviewResult,
  fix: (List[ReviewIssue]) => IgnoredIssues
)(using FlowContext): IgnoredIssues

case class IgnoredIssues(issues: List[IgnoredIssue]) derives Schema, ConfiguredJsonValueCodec:
  def ++(other: IgnoredIssues): IgnoredIssues = IgnoredIssues(issues ++ other.issues)
  def nonEmpty: Boolean = issues.nonEmpty

case class IgnoredIssue(issue: ReviewIssue, reason: String) derives Schema, ConfiguredJsonValueCodec

/** Convenience: review + fix loop with parallel reviewers and optional linter. Built on fixLoop. */
def reviewAndFix(
  coder: LlmTool[?],
  sessionId: SessionId[?],
  reviewers: List[LlmTool[?]],
  task: String,
  lintCommand: Option[String] = None,
  confidenceThreshold: Double = 0.7
)(using FlowContext): IgnoredIssues

/** Built-in planning: interactive session that produces a structured plan. */
def plan[P: Schema: ConfiguredJsonValueCodec](
  prompt: String,
  llm: LlmTool[?] = claude
)(using FlowContext): P

/** Built-in lint: run a command, summarize errors via LLM, return issues. */
def lint(
  command: String,       // e.g., "scalac ..." or "eslint ."
  llm: LlmTool[?] = claude.haiku
)(using FlowContext): ReviewResult
```

#### FlowContext

Tools are created during `orca` initialization and provided via `FlowContext`:

```scala
trait FlowContext:
  val claude: ClaudeTool
  val codex: CodexTool
  val git: GitTool
  val gh: GitHubTool
  val fs: FsTool
  val userPrompt: String
  def emit(event: OrcaEvent): Unit

case class Usage(inputTokens: Long, outputTokens: Long, cost: Option[BigDecimal])
```

No hidden type parameter — `claude` and `codex` are concrete typed fields. Session IDs from `claude` and `codex` are distinct types at compile time.

### Structured I/O

The LLM tool accepts and produces **typed data** via case classes:

- **Input**: serialized via the `AgentInput` typeclass (String → pass-through, case class → JSON)
- **Output**: the library generates JSON Schema from the output type's `Schema` (via `TapirSchemaToJsonSchema`), includes it in the prompt, and deserializes the LLM's JSON response via `ConfiguredJsonValueCodec`

Case classes can derive both in one line:

```scala
case class TaskPlan(tasks: List[Task]) derives Schema, ConfiguredJsonValueCodec
```

#### Prompt construction

The prompt sent to the backend is assembled from a pluggable `PromptTemplate`:

```scala
trait PromptTemplate:
  /** Autonomous: agent must produce structured output and stop. */
  def autonomous(input: String, outputSchema: String, config: LlmConfig): String
  /** Interactive: includes ORCA_DONE termination instructions. */
  def interactive(input: String, outputSchema: String, config: LlmConfig): String
```

Custom templates can be provided via `orca(promptTemplate = ...)`.

The `<<<ORCA_DONE>>>` marker signals task completion, not intermediate questions. In autonomous mode, no questions are allowed — the agent must produce output or fail.

For **headless** calls, the backend returns JSON on stdout — the library parses it directly.

For **interactive** calls, a backend-specific mechanism detects the marker and terminates the session (see backends below). The orchestrator reads the JSON payload from a **sentinel file** at `/tmp/orca-<session-id>.json`.

If the response fails to parse against the output schema, the library retries with a corrective prompt. This counts against the retry budget.

### Events and interaction

All agent output — streaming text, tool invocations, stage transitions, costs — flows through a typed event system:

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

Events are dispatched synchronously. The library emits events automatically for every LLM call, tool invocation, and stage transition.

**Interaction** is more than just listening for events — it's bidirectional. An `Interaction` handles both displaying output to the user **and** receiving user commands during interactive stages. It registers its own event listeners on startup:

```scala
trait Interaction:
  /** Register listeners for this interaction channel. */
  def listeners: List[OrcaListener]
  /** Handle an interactive agent session (terminal handoff, Slack message threading, etc.). */
  def runInteractive(handle: InteractiveHandle[?]): Unit
```

Built-in implementations:

- `TerminalInteraction` (default) — JLine 3 + fansi for styled output, `os.Inherit` for interactive agent sessions, spinners for autonomous stages
- `SlackInteraction(channel)` — posts agent output as threaded messages, receives user replies as input

Additional standalone listeners can be added for telemetry:

```scala
orca(interaction = TerminalInteraction, listeners = List(CostTracker)):
  claude.result[TaskPlan].interactive(userPrompt)
```

`CostTracker` accumulates `TokensUsed` events and prints a summary at flow end.

#### Helper functions

Flows report progress via helper functions (which emit events internally):

```scala
def stage[T](name: String)(body: => T)(using FlowContext): T  // emits StageStarted/StageCompleted
def fail(message: String)(using FlowContext): Nothing          // emits Error event, then throws OrcaFlowException
```

Usage: `stage("Planning") { claude.result[TaskPlan].interactive(userPrompt) }`

### Backend abstraction

```scala
trait LlmBackend[B <: Backend]:
  def prepareWorkspace(config: LlmConfig, outputSchema: String, workDir: Path)(using Ox): Unit
  def runHeadless(prompt: String, config: LlmConfig, workDir: Path): LlmResult[B]
  def continueHeadless(sessionId: SessionId[B], prompt: String, config: LlmConfig, workDir: Path): LlmResult[B]
  def launchInteractive(prompt: String, config: LlmConfig, workDir: Path): InteractiveHandle[B]
  def resumeInteractive(sessionId: SessionId[B], prompt: String, config: LlmConfig, workDir: Path): InteractiveHandle[B]

case class LlmResult[B <: Backend](sessionId: SessionId[B], output: String, usage: Usage)

trait InteractiveHandle[B <: Backend]:
  def awaitTermination(): LlmResult[B]
```

## Technical decisions

### Backend: Claude Code

**Headless invocation**:
```bash
claude -p "<prompt>" \
  --output-format json \
  --append-system-prompt-file <path> \
  --allowedTools "Read,Edit,Bash" \
  --permission-mode acceptEdits \
  --resume <session_id>
```

- JSON output includes `session_id`, `result`, `usage`, `stop_reason`
- Streaming via `--output-format stream-json` (NDJSON)
- Session resumed with `--resume <id>`

**Interactive invocation**:
```bash
claude "Here is your task: ..." \
  --session-id <pre-assigned-uuid> \
  --append-system-prompt-file <path>
```

- Positional argument (without `-p`) sends an initial prompt while keeping the session interactive
- `--session-id <UUID>` pre-assigns a session ID
- Sessions are saved on exit (including SIGINT) and resumable via `--resume <id>`

**Stop hook** (`.claude/settings.json`): fires after each agent turn, receives JSON on stdin with `session_id` and `transcript_path`. Checks transcript for `<<<ORCA_DONE>>>` marker → writes sentinel file → exits with code 2 (Claude Code interprets this as a hook failure that halts the agent's turn). Orchestrator detects sentinel, sends SIGINT, reads result.

**System prompt**: injected via `--append-system-prompt-file`.

### Backend: Codex (OpenAI)

**Headless**: `codex exec --json --full-auto` — JSONL event stream on stdout. Session ID from `thread.started` event. Structured output via `--output-schema`.

**Interactive**: app-server architecture (`codex app-server --listen ws://...`). JSON-RPC over WebSocket: `thread/start`, `turn/start`, `thread/resume`. User attaches TUI via `codex --remote`. Termination: orchestrator detects `<<<ORCA_DONE>>>` in streamed events and stops sending turns.

**App-server lifecycle**: lazily spawned on first call, reused across the flow (each call = separate thread), shut down via Ox `useInScope`. Crash → auto-restart.

**Stop hook semantics**: Codex uses JSON stdout `{"continue": false}` (not exit code 2). For app-server mode, hooks are unnecessary — orchestrator controls turns directly.

**Key backend differences**:

| Concern | Claude Code | Codex |
|---|---|---|
| Headless | `claude -p` | `codex exec --json` |
| Structured output | `--output-format json` | `--json` + `--output-schema` |
| System prompt | `--append-system-prompt-file` | `AGENTS.md` or `--config developer_instructions` |
| Auto-approve | `--permission-mode`, `--allowedTools` | `--full-auto`, `--ask-for-approval never` |
| Session ID | `--session-id <UUID>` (pre-assigned) | `thread.started` event / `thread/start` RPC |
| Interactive | Subprocess + stop hook | App-server (WebSocket) + TUI attachment |
| Stop mechanism | Hook exit code 2 | Hook `{"continue": false}` / stop sending turns |

### Dependency stack

| Library | Purpose | Notes |
|---|---|---|
| **Scala 3.x LTS** | Language | Context functions, opaque types, enums, derives |
| **mainargs** | CLI argument parsing | Used internally by `orca`; zero-dep |
| **sttp-client** | HTTP / WebSocket | Ox backend; Codex app-server JSON-RPC |
| **JLine 3** | Terminal control | Raw mode, signals, TTY handover |
| **fansi** | Styled output | Colored strings with correct length semantics |
| **os-lib** | Subprocesses | `os.Inherit`/`os.Pipe` redirects |
| **jsoniter-scala** | JSON codec | `derives ConfiguredJsonValueCodec` |
| **tapir (apispec-docs)** | JSON Schema | `TapirSchemaToJsonSchema` from `Schema` |
| **Ox** | Concurrency + retries | Virtual threads, `par`, `retry`, `Schedule` |
| **scribe** | Logging | Async handler |

### Concurrency model

Ox structured concurrency with virtual threads. Multiple reviewer agents run concurrently via `ox.par`. Each reviewer's `prepareWorkspace` creates an isolated temp directory so hook configs don't collide. For Codex, concurrent reviewers share the same app-server process but use separate threads.

### Local development workflow

```bash
# Terminal 1: continuous local publish
cd orca && sbt ~publishLocal

# Terminal 2: run the script
scala-cli run --ttl 0 my-flow.sc
```

Script references SNAPSHOT: `//> using repository ivy2Local` + `//> using dep com.virtuslab::orca:0.1-SNAPSHOT`. `--ttl 0` defeats Coursier's cache. Production: Maven Central.

## Example flows

### Development flow

A complete code-review-fix-PR cycle using built-in library functions:

```scala
//> using dep "com.virtuslab::orca:0.1"
//> using jvm 21
import orca.*

case class TaskPlan(tasks: List[Task], generalPrompt: String) derives Schema, ConfiguredJsonValueCodec
case class Task(description: String, acceptanceCriteria: String) derives Schema, ConfiguredJsonValueCodec
case class CodingResult(success: Boolean, message: String) derives Schema, ConfiguredJsonValueCodec

orca:
  val reviewers = List(
    claude.sonnet.withSystemPrompt("Review for performance issues: memory leaks, O(n²), unnecessary allocations"),
    claude.sonnet.withSystemPrompt("Review for readability: naming, structure, unnecessary complexity")
  )

  // 1. Plan interactively
  val (sessionId, taskPlan) = stage("Planning"):
    claude.result[TaskPlan].interactive(s"Analyze and break into tasks: $userPrompt")

  // 2. Create feature branch
  git.createBranch(claude.haiku.ask(s"Short branch name for: $userPrompt"))

  // 3. Code, review, fix, commit
  for task <- taskPlan.tasks do
    stage(s"Coding: ${task.description}"):
      claude.result[CodingResult].continueSession(
        sessionId,
        s"${taskPlan.generalPrompt}\n\nCurrent task:\n${task.description}",
        LlmConfig(autoApprove = AutoApprove.All)
      )

    reviewAndFix(
      coder = claude, sessionId = sessionId,
      reviewers = reviewers,
      task = task.description
    )

    git.commit(s"feat: ${task.description}")

  // 4. Create PR
  git.push()
  gh.createPr(title = s"feat: $userPrompt", body = taskPlan.generalPrompt)
```

### Planning flow

```scala
//> using dep "com.virtuslab::orca:0.1"
//> using jvm 21
import orca.*

case class ProjectPlan(epics: List[Epic]) derives Schema, ConfiguredJsonValueCodec
case class Epic(title: String, tasks: List[EpicTask]) derives Schema, ConfiguredJsonValueCodec
case class EpicTask(title: String, description: String, prompt: String) derives Schema, ConfiguredJsonValueCodec

orca:
  val existingDocs = fs.list("docs/**/*.md").map(fs.read)
  val (sessionId, projectPlan) = claude.result[ProjectPlan].interactive(existingDocs)

  for epic <- projectPlan.epics do
    val refined = claude.result[Epic].prompt(epic, LlmConfig(
      systemPrompt = Some("Refine task descriptions and write detailed coding prompts.")
    ))
    fs.write(s"plans/${epic.title.toLowerCase.replace(" ", "-")}.json",
      writeToString(refined))
```

### Batteries-included flow

Full development lifecycle — plan, code, lint, review, PR, CI fix loop, ignored issues:

```scala
//> using dep "com.virtuslab::orca:0.1"
//> using jvm 21
import orca.*

orca:
  // 1. Plan interactively
  val (sessionId, taskPlan) = stage("Planning"):
    claude.result[TaskPlan].interactive(userPrompt)

  git.createBranch(claude.haiku.ask(s"Branch name for: $userPrompt"))

  // 2. Code each task, review, fix, commit
  var allIgnored = IgnoredIssues(Nil)

  for task <- taskPlan.tasks do
    val result = stage(s"Coding: ${task.description}"):
      claude.result[CodingResult].continueSession(
        sessionId, task.description,
        LlmConfig(autoApprove = AutoApprove.All)
      )
    if !result.success then
      fail(s"Coding failed: ${result.message}")

    // LLM picks which reviewers are relevant for this change
    val reviewContext = claude.result[ReviewContext].prompt(
      s"Summarize the changes for task: ${task.description}\n${git.diff()}"
    )
    val selectedReviewers = claude.result[SelectedReviewers].prompt(
      s"Which of these reviewers are relevant?\n${defaultReviewers.map(_.name)}\nChanges: ${reviewContext.summary}"
    )

    // Lint + review + fix loop — returns any deliberately ignored issues
    val ignored = reviewAndFix(
      coder = claude, sessionId = sessionId,
      reviewers = selectedReviewers.pick(defaultReviewers),
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

  // 4. Wait for CI, fix any build failures
  val buildIgnored = fixLoop(
    evaluate = () =>
      val status = gh.waitForBuild(pr, timeout = 30.minutes)
      if status.success then ReviewResult.empty
      else claude.result[ReviewResult].prompt(s"Summarize build failures:\n${status.log}"),
    fix = issues =>
      claude.result[IgnoredIssues].continueSession(sessionId, issues.mkString("\n"))
  )

  if buildIgnored.nonEmpty then
    gh.writeComment(pr, s"Ignored build issues:\n${buildIgnored.format}")
```

## Roadmap

### Near-term

- **Cost tracking**: `CostTracker` listener accumulates `Usage` events, prints summary at flow end.
- **Build status polling**: `gh.waitForBuild(pr, timeout)` — polls until completion or timeout.
- **AI reviewer selection**: a step where the LLM examines the diff and selects which reviewers are appropriate.

### Medium-term

- **Progress tracker**: a component that tracks flow progress and can restore to the latest checkpoint on restart. Built-in stages (`plan`, `reviewAndFix`, `lint`) cooperate with the tracker — each reports completion, and on restart the tracker skips already-completed stages. Checkpoints are stored as git tags, commits, or metadata files. The tracker is registered as an `OrcaListener` so it observes `StageCompleted` events automatically.
- **Task tracking in issue trackers**: create GitHub Issues / Linear tasks from plans.
- **Sonar / static analysis integration**: feed into the review loop.
- **Slack interaction channel**: substitute terminal for Slack in interactive stages.

### Long-term

- **Sandbox / remote execution**: run agent sessions in Docker/Firecracker or on a remote machine.
- **Devflow development mode**: describe a workflow in natural language, generate the `.sc` script.
- **Knowledge graph**: structured project context for agents to query.
