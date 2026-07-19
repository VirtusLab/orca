# Orca Shell — research plan

Goal: design an "Orca Shell" — a separate, add-on module with its own executable
(launched via scala-cli) providing a welcome wizard, a main menu (run a flow,
create a flow via a harness, continue a session, re-configure, exit), while
keeping direct `scala-cli run flow.sc` usage working. Research feeds an ADR
that will drive the implementation plan.

Each topic below produces a result file `NN-<slug>.md` in this directory.
Method column notes whether the topic is codebase archaeology, external
research, or a design question that gets a proponent/skeptic adversarial pass.

## Topics

### 1. Global-state audit (`01-global-state.md`)
Inventory every piece of process-global mutable state that could leak between
consecutive `flow()` runs in one JVM, and classify each as: already safe /
needs cleanup between runs / needs redesign. Known suspects from a first pass:
`FlowLock.acquireProcess` (process-wide `AtomicBoolean`, released in `finally`
— but semantics say "one flow per process"), `System.exit(1)` in `flow()` on
failure, `OrcaLog.start()` mutating the global logback root logger,
`installUncaughtExceptionHandler` (install-once, holds no run state),
`Thread.setDefaultUncaughtExceptionHandler`, any `object`-level `var`s,
system-property or env mutation, JLine terminal singletons, static jsoniter
codecs (safe), daemon threads/executors that outlive a run. Method: codebase
sweep (subagent), verified by a second skeptic pass looking for what the first
missed.

### 2. In-process flow execution (`02-in-process-execution.md`)
The shell must run a selected `.sc` flow script *in-process*. The shell itself
is a scala-cli-launched program; flow scripts are also scala-cli scripts with
their own `//> using` directives. Questions: how to compile+load a flow script
inside the running shell JVM (embedded dotty — already a `flow` dependency for
the CC test suite — plus coursier for deps? scala-cli as a library? BSP? or is
"in-process" best served by same-terminal subprocess with shared stdio and the
in-process requirement re-scoped?), how the script's dependency set (possibly a
different orca version!) interacts with the shell's own classpath, how
`System.exit` in `flow()` is contained (exit-free entry point `runFlow` exists,
`private[orca]`), and how the JLine shell hands the terminal to
`TerminalInteraction` and gets it back. Method: codebase + external research,
then proponent/skeptic adversarial pass on the chosen mechanism.

### 3. CLI/interactive-shell libraries (`03-cli-libraries.md`)
Survey Scala and Java libraries for ergonomic interactive CLIs: menus,
single-choice select, multi-step wizards, line editing, colors. Candidates to
evaluate: JLine 3 (already a runner dependency; also `jline-console-ui` for
select prompts), cue4s, Lanterna, tui-scala, Text-IO, picocli (arg parsing
only?), plain fansi + readLine. Criteria: select-list/wizard support out of the
box, Scala 3 friendliness, native-terminal behaviour (raw mode, Ctrl-C), being
launched under scala-cli, maintenance status, dependency weight, coexistence
with `TerminalInteraction` during an in-process flow run. Method: external
research (context7/web), then adversarial pass comparing top 2 candidates.

### 4. Shell packaging & launch story (`04-packaging-launch.md`)
The shell is "its own executable, launching scala-cli, where the shell is
really implemented". Questions: what the executable actually is (a tiny shim
script `orca-shell` that execs `scala-cli run` on a published `.sc`? a
coursier bootstrap? `scala-cli --power package`?), how users install it, how
the shell knows its own version and the matching orca artifacts, new sbt module
(`shell`) layout and what it depends on (runner? flow?), and how built-in flows
ship (resources in the shell jar vs fetched from the repo at a git tag).
Includes: where built-in flows live in the repo (move out of `examples/`).
Method: codebase (README install story, build.sbt) + external (scala-cli
packaging options).

### 5. Flow discovery & well-known paths (`05-flow-discovery.md`)
Establish the well-known locations for flow scripts: project
(`{workDir}/.orca/flows/`? — check existing `.orca` contents for conflicts:
`flow.lock`, progress store, `settings.properties`), user-global
(`$XDG_CONFIG_HOME/orca/flows/`), and built-in (shipped with the shell).
Listing format: filename + description, where description = first line of the
file if it is a `//` comment (interaction with `//> using` directives on the
first lines — scala-cli directive conventions matter here). Also: add such
descriptions to existing flows; decide precedence/shadowing when the same
filename exists at several levels. Method: codebase + scala-cli directive
rules; small design section.

### 6. Welcome wizard & settings write (`06-wizard-settings.md`)
First-run detection (global settings file absent?), harness auto-detection
(which binary names to probe on PATH per backend: `claude`, `codex`,
`opencode`, `pi`, `gemini` — verify each backend's actual launcher command in
the code), the preferred-harness selection for planning/coding/review (ordered
consistently with the harness enum — find the authoritative enum), and writing
the global settings file (does `SettingsFile` support writing, or read-only
today? preserve comments/unknown keys?). Method: codebase sweep.

### 7. Teaching a harness the Orca API (`07-teaching-api.md`)
For "create a new flow": the shell starts the chosen harness with a prompt
("write a xyz global/project flow"). How does the agent learn Orca's API?
Options: (a) link to README on GitHub (agent fetches), (b) embed the README
(pinned to the running release via git tag) into the prompt, (c) point the
agent at a tool like VirtusLab/cellar to explore the API interactively, (d) a
purpose-built condensed API reference doc shipped with the shell. Research
cellar (what it is, maturity, how an agent would use it), README token cost,
staleness/version-pinning trade-offs. Method: external research + adversarial
proponent/skeptic pass.

### 8. Session tracking & continuation (`08-session-continuation.md`)
After a flow completes, the shell should offer "continue a session started in
the flow". Questions: which backends support resuming by persistent session ID
and with what CLI invocation (claude `--resume`, codex `resume`, opencode,
pi, gemini — verify per backend, including interactive-mode resume, since the
user would continue the conversation interactively), where Orca currently
captures session IDs (`Session.scala`, drivers, events), how a completed
in-process flow reports its sessions to the shell (event listener collecting
session-started events? a run manifest file under `.orca`?), and what
"continue" launches (the harness's own interactive UI in the foreground).
Method: codebase + external per-harness research; adversarial pass on the
reporting mechanism.

## Execution order

Topics 1, 5, 6 and the codebase half of 8 are independent codebase sweeps —
run first, in parallel. Topics 3, 7 and the external half of 8 are external
research — run in parallel with the sweeps. Topic 2 and 4 depend lightly on
1/3 results and get adversarial passes. Synthesis: ADR
`adr/0021-orca-shell.md` after all result files are in. (done — the ADR exists)

## Outcome (all topics researched; skeptic passes on 1, 2, 3, 7, 8)

Decisions the result files converged on, after reconciling cross-topic
dependencies:

1. **Flows run as `scala-cli run` subprocesses, not in-process** (02,
   skeptic-upheld). This overrides the brief's literal "in-process" wording,
   on a recorded case: measured warm start is <1 s, no menu feature needs
   in-process-only capabilities, and every in-process mechanism (exit
   containment, terminal handover, forced version, state hygiene) buys back
   what subprocess has natively. The shell forces its own orca version via
   `--dep` (verified to override the script's pin), falling back to
   pin-honouring + a visible degradation notice on compile failure. This
   renders the global-state audit (01) moot for the shell — it stands as the
   justification record and the checklist if in-process is revisited.
2. **UI: JLine 3.30.x + jline-console-ui** (03), with mandatory tty-gating
   and a numbered-menu fallback (reproduced EOF NPE on non-tty stdin). The
   shell shares the runner's already-pinned JLine stack.
3. **Packaging** (04): new `shell` sbt module → `org.virtuslab::orca-shell`;
   executable = shim script (`install.sh` → `~/.local/bin/orca`) exec-ing
   `scala-cli run --dep …orca-shell:latest.release`. Built-in flows move
   `examples/*.sc` → `flows/`, ship as jar resources + generated index,
   extracted to `~/.cache/orca/shell/<version>/flows/` for scala-cli to run.
4. **Discovery** (05): project `{workDir}/.orca/flows/` (committed, ADR 0019
   polarity), global `$XDG_CONFIG_HOME/orca/flows/`, built-in; precedence
   project > global > built-in with labeled shadowing; description = first
   non-directive `//` comment line in the leading block (verified safe before
   `//>` directives).
5. **Wizard** (06): harness-only choice in `BackendTag` order via
   `command -v` probes; global settings written fresh (absent) or via
   surgical line-level update preserving comments (present); first-run =
   file absent or all three roles unset.
6. **Teaching the API** (07, skeptic-amended): bundled README + example flows
   extracted into the harness's workspace (`.orca/cache/orca-api-<ver>/`;
   for global flows the harness runs with cwd `~/.config/orca/`) —
   out-of-workspace reads are not uniformly approval-free.
7. **View & edit flows** (03 addendum, post-review addition): view = syntax-
   highlighted print via `SyntaxHighlighter` from the already-shipped jline
   bundle jar + an own-written `scala.nanorc` (no license-compatible one to
   vendor); edit = `$VISUAL` > `$EDITOR` > `vi`, spawned git-style via
   `sh -c`; built-ins are never edited in the cache — "customize" copies to
   project/global first, shadowing the built-in.
8. **Session continuation** (08, skeptic-amended): new Agent-layer
   `SessionCommitted` event carrying `persistableWireId`; per-run
   manifests in `.orca/cache/runs/` written atomically per event; resume =
   claude/opencode by id (high), codex by id (medium-high), gemini via
   `--list-sessions` index (medium), pi disabled with reason.
