# Gates and checks

Besides the reviewers, each review round can run three kinds of verification
that do not involve an LLM: a format gate, a lint gate, and Scala checks. This
page describes each of them, and how to turn the gates on and off.

## Format

The `formatCommands` run before each review round, in the flow's working
directory. This way the reviewers never see formatting noise in the change set.

## Lint

The `lint` gate runs alongside the reviewers, each round. It is described by a
`Lint(commands, agent)` value: the shell commands to run, plus the cheap agent
that summarises their output into a
[`ReviewResult`](../api/data-structures.md#review).

The lint gate is also available as a standalone call, which you can use inside
a fork as well. It comes in two variants:

- `lint(commands, agent, instructions?)` runs the commands in order with
  `bash -c`, all of them even if one fails, and then has `agent` turn the
  labelled output into a `ReviewResult`. Long output is written under
  `.orca/cache/` for the agent to read, so it cannot overflow the context.
- `lint(commands, summariser, instructions)` works as above, but summarises
  into an existing `Lint.summariser(agent)` conversation, so that a gate which
  runs several times in one stage resumes the same session. It returns a
  `LintReport`. The review loop does this for you.

```{warning}
Do not reuse a summariser after it has reported findings: it may repeat them
when a later run no longer shows them.
```

Note that the review loop does not run the `test` commands, as it stays
deliberately cheap. If you want to run the tests, read them as
`summon[FlowContext].stackSettings.test` (see
[Data structures](../api/data-structures.md#settings)) and run them in a stage
of your own.

## Checks

A `ReviewCheck` is Scala code with a `name` and an `evaluate(): ReviewResult`
method: for example a benchmark, an HTTP probe, or an assertion. Pass it in the
`checks` list of either review call, and its findings go to the fixer together
with the reviewers' findings.

Checks run one at a time, after the format commands and before the reviewers
and the lint gate, so a check that builds or times the code has the machine to
itself. A check must not modify sources. Keep a finding's title the same across
rounds and put the measurements in its description, because the loop matches a
check's finding to the one it already holds by its title and file.

With no reviewers at all, the loop just evaluates the check and fixes. For
example:

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
[Capabilities](capabilities.md). Note that in one `reviewThenFix` call a check
can run up to three times: before the review, after the fix, and after the
second fix.

## Turning gates on and off

The `formatCommands` and `lint` parameters of `reviewThenFix` and
`reviewAndFixLoop` are `Configured` values. The default reads the project's
[stack settings](../using/settings.md); `Off` disables the gate; and
`Use(value)` gives one explicitly:

```scala
enum Configured[+A]:
  case FromSettings   // resolve from the run's stack settings (the default)
  case Off            // explicitly disabled for this call
  case Use(value: A)  // explicit value; settings ignored
```

`FromSettings` uses `stackSettings.format` for `formatCommands`, and
`Lint(stackSettings.lint, reviewAgent.cheap)` for `lint`. An empty command list
means no gate, so empty settings behave like `Off`. If you want the format gate
only, pass `lint = Configured.Off`.

## Recording your own findings

`OpenFinding.custom(title, reason, location)` is an open finding that a flow
records itself, for example when a gate it runs outside the loop still fails.
Add it to the `OpenFindings` handed to the PR step, or pass it in
`priorOpenFindings` so that a loop's reviewers see it.
