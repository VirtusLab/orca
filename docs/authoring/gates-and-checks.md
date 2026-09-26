# Gates and checks

Each review round can run three kinds of non-LLM verification alongside the
reviewers: a format gate, a lint gate, and Scala checks.

## Format

`formatCommands` run before each review round, in the flow's working
directory, so reviewers never see formatting noise.

## Lint

`lint` runs alongside the reviewers each round. A `Lint(commands, agent)` is the
shell commands plus the cheap agent that summarises their output into a
[`ReviewResult`](../api/data-structures.md#review).

The standalone call is available too, and works inside a fork:

| Call | Does |
|---|---|
| `lint(commands, agent, instructions?)` | runs the commands in order with `bash -c`, all of them even if one fails, then has `agent` turn the labelled output into a `ReviewResult`. Long output is written under `.orca/cache/` for the agent to read, so it cannot overflow the context |
| `lint(commands, summariser, instructions)` | as above, summarising into an existing `Lint.summariser(agent)` conversation, so a gate run several times in one stage resumes the session; returns a `LintReport`. Do not reuse a summariser after it has reported findings: it may repeat them when a later run no longer shows them. The review loop does this for you |

The `test` commands are not run by the review loop, which stays deliberately
cheap. Read them as `summon[FlowContext].stackSettings.test` (see
[Data structures](../api/data-structures.md#settings)) and run them in a stage
of your own.

## Checks

A `ReviewCheck` is Scala code with a `name` and `evaluate(): ReviewResult`: a
benchmark, an HTTP probe, an assertion. Pass it in the `checks` list of either
review call; its findings go to the fixer with the reviewers'.

Checks run one at a time, after the format commands and before the reviewers
and the lint gate, so a check that builds or times the code has the machine to
itself. A check must not modify sources. Keep a finding's title the same across
rounds and put measurements in its description: the loop matches a check's
finding to the one it already holds by its title and file. With no reviewers,
the loop just evaluates the check and fixes:

```scala
val benchmark = new ReviewCheck:
  def name = "benchmark"
  def evaluate()(using ctx: FlowContext, ev: InStage): ReviewResult =
    val ms = os.proc("./bench.sh")    // os-lib
      .call(cwd = ctx.workDir, stderr = os.Pipe).out.trim().toInt
    if ms <= 200 then ReviewResult.empty
    else ReviewResult(List(ReviewFinding(Title("Request too slow"),
      s"p99 is $ms ms; the target is 200 ms", location = None,
      suggestion = None, reopens = None)))

stage("Speed up"):
  reviewAndFixLoop(
    coderSession = session,
    reviewers = Nil,
    task = Task(Title("Make requests faster"), ""),
    lint = Configured.Off,
    checks = List(benchmark)
  )
```

`InStage` is the capability every agent run takes, see
[Capabilities](capabilities.md). In one `reviewThenFix` call a check can run up
to three times: before the review, after the fix, and after the second fix.

## Turning gates on and off

`formatCommands` and `lint` on `reviewThenFix` and `reviewAndFixLoop` are
`Configured` values. The default reads the project's
[stack settings](../using/settings.md); `Off` disables the gate; `Use(value)`
gives one explicitly:

```scala
enum Configured[+A]:
  case FromSettings   // resolve from the run's stack settings (the default)
  case Off            // explicitly disabled for this call
  case Use(value: A)  // explicit value; settings ignored
```

`FromSettings` uses `stackSettings.format` for `formatCommands` and
`Lint(stackSettings.lint, reviewAgent.cheap)` for `lint`. An empty command list
means no gate, so empty settings behave like `Off`. For format-only, pass
`lint = Configured.Off`.

## Recording your own findings

`OpenFinding.custom(title, reason, location)` is an open finding a flow records
itself, say a gate it runs outside the loop still failing. Add it to the
`OpenFindings` handed to the PR step, or pass it in `priorOpenFindings` so a
loop's reviewers see it.
