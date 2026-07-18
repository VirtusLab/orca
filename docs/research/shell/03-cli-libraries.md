> Final. Skeptic verdict (§Final ranking): JLine 3.30.x + jline-console-ui
> UPHELD with conditions — tty-gate every ConsoleUI prompt (EOF NPE on
> non-tty stdin is reproduced, not theoretical) and ship a numbered-menu
> fallback as a requirement.

# 03 — CLI / interactive-shell libraries

Survey of Scala and Java libraries for the Orca Shell's interactive layer:
welcome wizard (multi-step), single-choice select menus (arrow keys), text
prompts, colors, line editing. Target: Scala 3 / JVM 21, launched via
scala-cli, direct-style (Ox) codebase. Research date: 2026-07-18.

## Where Orca stands today

- `org.jline:jline` **3.28.0** (the all-in-one bundle jar, zero transitive
  deps) is already a `runner` dependency
  (`/home/adamw/orca/project/Dependencies.scala`), alongside
  `com.lihaoyi::fansi` 0.5.0 for styling.
- Usage today is minimal: `orca.runner.terminal.ConversationRenderer.JLinePrompter`
  builds a lazy system terminal (`TerminalBuilder.builder().system(true).dumb(true)`)
  plus a `LineReader`, uses `readLine(prompt)` for approval prompts, and maps
  `UserInterruptException | EndOfFileException` to `PromptOutcome.Interrupted`.
  Plain JLine line-reading only — **no select menus** are used or provided by
  the reader alone.
- Two seams matter for the shell:
  - `TerminalInteraction.start(prompter = ...)` accepts an injected
    `ConversationRenderer.Prompter`. The `JLinePrompter` scaladoc explicitly
    says: process-scoped, its lazy terminal cannot re-initialize after
    `close()`, "Inject a custom Prompter for embedded/multi-run scenarios."
    So the shell can hand its own terminal-backed prompter to an in-process
    flow run instead of letting the runner open a second system terminal.
  - `TerminalOutput` writes to a plain `PrintStream` (stderr) — it does not
    own the terminal, so it coexists with whatever owns raw mode as long as
    nothing is mid-prompt.

## Version landscape (Maven Central, checked 2026-07-18)

| Artifact | Latest | Released | Notes |
|---|---|---|---|
| `org.jline:jline` (bundle) | 4.3.1 / **3.30.15** | 2026-06-30 / 2026-06-30 | 3.x and 4.x released in lockstep, same day |
| `org.jline:jline-console-ui` | 4.3.1 / 3.30.15 | 2026-06-30 | 73 KB jar; exists for both lines |
| `tech.neander:cue4s_3` | 0.0.13 | 2026-06-24 | repo pushed 2026-06-24, 45 stars |
| `com.googlecode.lanterna:lanterna` | 3.1.5 (3.2.0-alpha1 stale) | 2026-03-15 | active-ish; zero deps |
| `com.olvind.tui:tui_3` (tui-scala) | 0.0.10 | 2026-03-26 | repo renamed to `oyvindberg/jatatui`, pushed 2026-05-17, 260 stars |
| `org.beryx:text-io` | 3.4.1 | 2020-04-17 | **dead** (6 years) |
| `info.picocli:picocli` | 4.7.7 | 2025-04-19 | arg parsing; no menus |
| `org.fusesource.jansi:jansi` | 2.4.3 | 2026-03-27 | ANSI enablement only |

Sources: `https://repo1.maven.org/maven2/.../maven-metadata.xml` +
per-artifact POM `Last-Modified` headers; GitHub repo API for activity.

Note: the `search.maven.org` solr index is stale (reported jline 3.26.3 as
latest); `repo1.maven.org` metadata is authoritative.

## Candidates

### 1. JLine 3 + `jline-console-ui` (ConsoleUI) — top choice

- Docs: https://jline.org/docs/modules/console-ui, source:
  https://github.com/jline/jline3/tree/master/console-ui
- ConsoleUI (Inquirer.js-inspired, formerly a separate `consoleui` project)
  was merged into the JLine repo and is released as `org.jline:jline-console-ui`
  in lockstep with every JLine release, including the maintained 3.30.x line.
  Not deprecated; actively documented on jline.org.
- **Prompt types**: input (readline editing, completion, masking), **list
  (single-choice, arrow keys)** *[amended — no j/k navigation; arrows/e/y
  only, see Skeptic review]*, checkbox (multi-select, space
  toggle), expandable choice (single-key answers), confirm (y/n). Page-size
  control, disabled items with reason, pre-checked items.
- **API**: `new ConsolePrompt(terminal)` (constructors also take a
  `LineReader` and `UiConfig`) → `getPromptBuilder()` →
  `createListPrompt()...addItem(...)...addPrompt()` →
  `prompt(...)` returning `Map<String, PromptResultItemIF>`. Java-builder
  flavored; a thin Scala 3 wrapper (~50–100 lines) makes it pleasant.
  A list of prompts passed at once behaves as a simple sequential wizard;
  branching between steps is coded by the caller. *[undersold — the
  `Function`-based `prompt` overload is a wizard engine with back-navigation;
  see Skeptic review]*
- **Ctrl-C**: prompts run inside `terminal.enterRawMode()`; interrupt
  surfaces as `UserInterruptException` (ConsolePrompt wraps
  `InterruptedIOException` from the reader) — catchable, so the shell decides
  "back to menu" vs "exit". Same exception model `JLinePrompter` already
  handles for `readLine`.
- **Criterion 7 (terminal handoff)** — the strongest story of any candidate:
  - `ConsolePrompt.prompt()` saves attributes, enters raw mode, and its
    `close()` path restores saved attributes, disables keypad mode, and
    emits a newline. **Between prompts the terminal is fully cooked** — no
    background stdin-reading thread, no persistent raw mode. JLine reads
    stdin only while a prompt/readLine is active.
  - Spawning an interactive harness (`claude`/`codex` TUI with inherited
    stdio) between prompts therefore needs no special handling; on return
    the shell just shows the next prompt.
  - For an in-process flow run: JLine's docs note there is conceptually one
    system terminal per JVM (https://jline.org/docs/terminal/), so the clean
    design is one shared `Terminal` owned by the shell, with a custom
    `Prompter` injected into `TerminalInteraction.start` — the seam exists
    today, and it also sidesteps the documented `JLinePrompter`
    can't-reopen-after-close limitation (01's skeptic shows the scaladoc
    overstates this — reopen works on Linux; the injection seam remains the
    right design). (Sequential use of two system
    terminals also works in practice, but sharing is strictly simpler.)
- **Versions/JVM 21**: stay on the **3.30.x** line for now. JLine 4.x
  requires Java 11+ and its default jar ships the FFM terminal provider
  compiled for Java 22+ (jline.org recommends the `jdk11` classifier on
  Java 11–21). 3.30.15 was released the same day as 4.3.1, so 3.x is not
  abandoned. Action: bump the runner pin 3.28.0 → 3.30.15 so bundle and
  console-ui agree.
- **Dependency weight**: bundle jar is 1.5 MB, zero transitive deps, and
  already on the classpath. Inspection of `jline-3.30.15.jar` shows it
  contains `org/jline/{terminal,reader,builtins,console,style,keymap,widget,jansi,nativ,utils}`
  but **not** `org/jline/consoleui` — so `jline-console-ui` (73 KB) must be
  added. Its POM declares a dependency on the modular `jline-builtins`;
  **exclude that transitive** (the bundle already provides those packages)
  to avoid duplicate classes from bundle-vs-modular mixing.
- **scala-cli**: already proven — `TerminalInteraction` runs under scala-cli
  today; `scala-cli run` execs a JVM with inherited stdio, so the system
  terminal resolves normally (with the existing `dumb(true)` fallback for
  non-tty).
- Windows: JLine's jni/jansi providers cover Windows consoles (bonus, not a
  requirement).

### 2. cue4s — runner-up

- Repo: https://github.com/neandertech/cue4s (Neandertech / Anton Sviridov).
  v0.0.13, 2026-06-24; Scala 3 only; JVM, JS, Native.
- **Prompts**: single choice (arrow keys), multi choice (windowed), text,
  int/float with validation, plus experimental case-class derivation for
  form-like wizards. Nicest Scala 3 API of the field:
  `Prompts.sync.use: p => p.singleChoice("...", List(...)).getOrThrow`;
  also `Future` and cats-effect variants (direct-style `sync` fits Ox).
- **Deps**: `fansi` (already ours) + **JNA 5.14** (~2 MB) for termios/console
  calls, + `snapshots-runtime`. Terminal handling is hand-rolled: JNA
  `tcsetattr` on Linux/macOS clearing `ICANON|ECHO`
  (`ChangeModeLinux.java`), native `getchar()` on a dedicated
  `cue4s-keyboard-input-thread`, `sun.misc.Signal` for WINCH resize.
- **Ctrl-C (criterion 3)**: on POSIX the raw mode **keeps `ISIG` enabled**,
  so Ctrl-C during a prompt raises SIGINT and **terminates the JVM** — a
  registered shutdown hook restores cooked mode and the cursor, but the
  shell cannot catch it and return to the menu without installing its own
  signal handler (which then fights the native-blocked reader thread). Only
  on Windows is byte 3 decoded in-band and surfaced as a catchable
  interruption. Contrast: JLine gives a catchable `UserInterruptException`
  everywhere.
- **Criterion 7 (terminal handoff)** — the weak spot:
  - `InputProviderImpl.evaluate` calls `changemode(1)` (raw) at prompt start
    but never `changemode(0)` at prompt end — cooked mode is restored only
    by `InputProvider.close()` (end of the `Prompts.sync.use` block) or the
    shutdown hook. **The terminal stays raw between prompts within one
    session**, so a long-lived `Prompts` handle spanning the menu loop would
    hand a raw terminal to a spawned TUI or an in-process flow run.
    Workaround: scope one `Prompts.sync.use` per menu interaction and close
    before launching anything — workable, easy to get wrong.
  - The keyboard thread blocks in **native `getchar()`** (libc-buffered
    stdin); `Thread.interrupt()` cannot unblock it, and libc buffering can
    swallow type-ahead destined for a subsequently spawned process.
  - WINCH handlers are installed per prompt via `sun.misc.Signal` and never
    removed.
  - It would also run *alongside* the runner's JLine prompter during an
    in-process flow, two independent raw-mode owners on one tty — no shared
    seam like ConsoleUI's `ConsolePrompt(terminal)`.
- Pre-1.0 (0.0.x, 45 stars), but actively developed and by a well-known
  Scala tooling author. Great API, immature terminal plumbing for our
  handoff-heavy use case.

### 3. Lanterna — overkill, as suspected

- https://github.com/mabe02/lanterna — 3.1.5 (2026-03-15), zero deps, repo
  active (pushed 2026-07-07). Ignore the stale `3.2.0-alpha1` (2020) that
  metadata reports as "latest".
- Full-TUI: `Screen`/alternate-buffer, `TextGUI` windows, dialogs. Menus
  mean adopting its full-screen interaction model — the shell's
  scrolling-log-plus-prompt model (and `TerminalOutput`'s stderr writes)
  doesn't fit. Terminal release via `stopScreen()` works, but everything
  else is heavyweight. No reason to prefer it over ConsoleUI for prompts.

### 4. tui-scala / jatatui — immature, wrong shape

- `oyvindberg/tui-scala` was renamed **`oyvindberg/jatatui`**
  (https://github.com/oyvindberg/jatatui, pushed 2026-05-17, 260 stars);
  artifacts still `com.olvind.tui:tui_3` 0.0.10 (2026-03-26). Ratatui-style
  immediate-mode full-screen TUI with native (crossterm-derived) bindings.
  Same overkill objection as Lanterna, plus 0.0.x maturity and native-lib
  weight. Not a fit.

### 5. Text-IO (beryx) — dead

- 3.4.1 released 2020-04-17; no release in six years. Numbered-choice
  selection (type a number), not arrow-key menus; Swing fallback terminal.
  Eliminated on maintenance and features.

### 6. picocli — not applicable

- 4.7.7 (2025-04-19). Argument parsing; `interactive = true` options only do
  echo-off single-value input (passwords). The JLine integration
  (`picocli-shell-jline3`) targets REPL command completion, not select
  menus or wizards. Useful only if the shell ever grows flag parsing —
  Orca already uses mainargs.

### 7. Jansi / fansi + hand-rolled menu on raw JLine

- Jansi 2.4.3 is only Windows-ANSI enablement + escape helpers; JLine
  bundles its own jansi fork (`org/jline/jansi` is inside the bundle jar).
  fansi stays our styling layer regardless.
- Hand-rolling a select menu on raw JLine (`enterRawMode` +
  `BindingReader` + `Display`) is ~150–250 lines and is essentially
  reimplementing ConsoleUI's list prompt without upstream maintenance.
  Only worth it if ConsoleUI's rendering proves too rigid; keep as fallback.

## Comparison against the criteria

| Criterion | JLine+ConsoleUI | cue4s | Lanterna | jatatui | Text-IO | picocli |
|---|---|---|---|---|---|---|
| 1. select+wizard OOTB | yes (list/checkbox/confirm/input; sequential batching) | yes (nicest API) | via full TUI | via full TUI | numbered only | no |
| 2. works under scala-cli tty | proven in repo | yes (JNA) | yes | yes (native libs) | yes | yes |
| 3. Ctrl-C catchable | yes (`UserInterruptException`) | **no on POSIX** (JVM dies) | app-handled | app-handled | no | n/a |
| 4. maintenance | 2026-06-30, lockstep 3.x/4.x | 2026-06-24, pre-1.0 | 2026-03-15 | 2026-03-26, 0.0.x | dead 2020 | 2025-04 |
| 5. dep weight | +73 KB (jline already present) | +JNA ~2 MB | +~1 MB | native bindings | — | — |
| 6. Scala 3 ergonomics | Java builders; thin wrapper needed | excellent | Java | Scala 3 | Java | Java |
| 7. terminal release/handoff | **clean**: raw only during prompt; shares `Terminal` with runner via `Prompter` seam | raw until session close; native getchar thread; no sharing seam | stopScreen, but model mismatch | full-screen takeover | n/a | n/a |

## Recommendation

1. **Top choice: JLine 3.30.15 bundle + `org.jline:jline-console-ui` 3.30.15**
   (transitive `jline-builtins` excluded), wrapped in a small Scala 3 facade.
   It wins on the decisive criterion 7: no background stdin readers, raw mode
   strictly scoped to an active prompt, attribute restore on close, a
   constructor that accepts an existing `Terminal` so the shell and the
   runner's `TerminalInteraction` (via an injected `Prompter`) share one
   terminal, and cooked-mode gaps in which a spawned claude/codex TUI can
   take the tty. It is also the only candidate already halfway in the
   dependency tree, with catchable Ctrl-C and same-day 3.x maintenance
   releases.
2. **Runner-up: cue4s 0.0.13** — the best pure-Scala-3 prompt API and
   actively developed, but rejected for now on criterion 7 (session-scoped
   raw mode, native `getchar` thread, no terminal-sharing seam) and
   criterion 3 (POSIX Ctrl-C kills the JVM). Worth rechecking at ~1.0 if the
   terminal plumbing matures.

### What the top choice does NOT give us (hand-roll list)

- **Wizard flow control**: back-navigation, conditional steps, and re-entry
  into the menu loop — ConsoleUI only batches prompts sequentially; the
  shell writes its own step state machine. *[undersold — the `Function`-based
  `prompt` overload is a wizard engine with back-navigation; see Skeptic
  review]*
- **Scala facade**: `ConsolePrompt`'s builder API and
  `Map<String, PromptResultItemIF>` results want a thin typed wrapper
  (enum-driven `select[A](title, options): Option[A]`, etc.).
- **Ctrl-C/ESC policy**: mapping `UserInterruptException` (and whether ESC
  cancels a list prompt — verify during spike (answered: yes, ESC → CANCEL —
  see Skeptic review)) to "back" vs "exit shell".
- **Welcome banner / styled headers**: fansi, as today.
- **Terminal-sharing glue**: constructing the shell's single system
  `Terminal`, passing it to `ConsolePrompt`, and implementing the
  shell-owned `Prompter` injected into `TerminalInteraction.start` (also
  avoids `JLinePrompter`'s no-reopen-after-close limitation).
- **Version alignment chore**: bump runner's jline 3.28.0 → 3.30.15 and add
  the console-ui exclusion rule so bundle/modular jars never mix.

### Spike checklist (for the adversarial pass)

- ConsoleUI list prompt under `scala-cli run` in a plain terminal and with
  redirected stdin (dumb-terminal degradation path).
- Ctrl-C mid-list-prompt: confirm `UserInterruptException` and clean
  attribute restore; then immediately re-prompt.
- Prompt → spawn `claude` (inherited stdio) → exit → re-prompt, checking
  echo/canonical state after the TUI exits (defensively re-assert sane
  attributes after any subprocess).
- Shared-terminal in-process run: shell terminal + injected `Prompter` +
  `TerminalOutput` animations writing to stderr concurrently.

## Sources

- https://jline.org/docs/modules/console-ui — ConsoleUI prompt types, API
- https://jline.org/docs/terminal/ — terminal lifecycle, single system terminal
- https://github.com/jline/jline3/blob/master/console-ui/src/main/java/org/jline/consoleui/prompt/ConsolePrompt.java — constructors, raw-mode enter/restore, `UserInterruptException`
- https://github.com/jline/jline3/releases — 3.30.x / 4.x release cadence; JLine 4 Java 11+ / FFM-on-22+ notes
- https://github.com/neandertech/cue4s — README, and sources
  `modules/core/src/main/scalajvm/cue4s/InputProviderImpl.scala`,
  `ChangeModeLinux.java`, `scala-jvm-native/KeyboardReadingThread.scala`,
  `modules/core/src/main/scala/CharCollector.scala`
- https://github.com/mabe02/lanterna, https://github.com/oyvindberg/jatatui
- https://picocli.info (interactive options), https://github.com/beryx/text-io
- repo1.maven.org maven-metadata.xml + POM Last-Modified headers for all
  version/date claims (fetched 2026-07-18)

## Skeptic review

Adversarial pass, 2026-07-18. Every verdict below is backed by something I
fetched, read, or executed — not by the proponent's citations.

### Claim verdicts

**UPHELD — repo-side claims.**
- `org.jline:jline` 3.28.0 pinned: `/home/adamw/orca/project/Dependencies.scala`
  line 12 (`val jline = "3.28.0"`), fansi 0.5.0 alongside.
- Orca targets Java 21: `build.sbt` `scalacOptions ++= Seq("-release", "21", ...)`
  and `javacOptions ++= Seq("--release", "21")`.
- The `prompter` seam is real, not invented:
  `TerminalInteraction.start(out, useColor, animated, workDir, prompter: ConversationRenderer.Prompter = ConversationRenderer.JLinePrompter)(using Ox, BufferCapacity)`
  at `runner/src/main/scala/orca/runner/terminal/TerminalInteraction.scala:74-81`.
  `JLinePrompter` (`ConversationRenderer.scala:296`) builds
  `TerminalBuilder.builder().system(true).dumb(true)` and maps
  `UserInterruptException | EndOfFileException` to `PromptOutcome.Interrupted`,
  as described.

**UPHELD — Maven Central facts** (fetched from repo1.maven.org, 2026-07-18).
- `org.jline:jline-console-ui` exists at both 3.30.15 and 4.3.1
  (maven-metadata.xml, lastUpdated 20260630); the 3.30.15 jar is 73,136 bytes.
- Package inside the jar is `org.jline.consoleui.*` (verified with `jar tf`) —
  **not** the dead `de.codeshelf.consoleui` fork.
- `jline-console-ui` POM declares compile-scope `jline-builtins` — the
  proposed exclusion is warranted; the bundle jar contains no
  `org/jline/consoleui/` package (grep count 0), so console-ui must be added.
- Bundle POM: all 18 dependency entries are `<optional>true</optional>` or
  test scope — "zero transitive deps" holds in effect.
- cue4s 0.0.13 is latest (lastUpdated 20260624); compile deps are fansi 0.5.0
  + JNA 5.14.0.
- The `jline-4.3.1-jdk11.jar` classifier exists (HTTP 200).

**UPHELD — ConsoleUI API and raw-mode scoping** (read
`ConsolePrompt.java` at tag `jline-3.30.15`).
- `public ConsolePrompt(Terminal terminal)` is real (line 45), plus
  `(Terminal, UiConfig)` and `(LineReader, Terminal, UiConfig)` — it accepts
  an existing Terminal, enabling the shared-terminal design.
- `prompt(...)` wraps `open()`/`close()` in try/finally; `open()` calls
  `terminal.enterRawMode()` + keypad_xmit, `close()` restores saved attributes
  + keypad_local. Raw mode is strictly per-`prompt()` call; no threads are
  spawned — reading happens on the calling thread. Cooked between prompts:
  confirmed in source.
- Ctrl-C: `ctrl('C')` is bound to INTERRUPT in every prompt keymap →
  `throw new UserInterruptException` (`AbstractPrompt.java:866,905`), and
  `ConsolePrompt` rethrows `InterruptedIOException` causes as
  `UserInterruptException` (lines 284-285). Source-verified; not exercised
  interactively.

**UPHELD — cue4s criterion-3/7 flaws** (read v0.0.13 sources on GitHub).
- `InputProviderImpl.evaluate` calls `changemode(1)` at start (line 61) and
  never `changemode(0)` on normal completion — restore happens only in
  `close()` (line 147) or the shutdown hook. Session-scoped raw mode:
  confirmed.
- `ChangeModeLinux.changemode` clears only `ICANON|ECHO` from `c_lflag`;
  ISIG stays set, so POSIX Ctrl-C delivers SIGINT (default: JVM death).
  In-band byte-3 handling exists only under
  `if Platform.os == Platform.OS.Windows` (`CharCollector.scala:73`).
- Keyboard thread calls JNA-native `getchar()`
  (`KeyboardReadingThread.scala:30`); `Thread.interrupt()` cannot unblock a
  native call. Bonus defect found while reading: `ChangeModeLinux` defines the
  correct Linux `c_cc` indices `VTIME=5`, `VMIN=6` but writes
  `c_cc[VTIME-1]`/`c_cc[VMIN-1]` — i.e. it appears to set VEOF and VTIME
  instead of VTIME/VMIN. Consistent with "immature terminal plumbing".
- Minor correction: `snapshots-runtime` is **test**-scope in the 0.0.13 POM,
  not a runtime dependency of consumers.

**AMENDED — "list prompt: arrow keys + vi j/k".** False in both lines.
`ListChoicePrompt.bindKeys` (3.30.15 `AbstractPrompt.java:858-868`, identical
in the 4.3.1 sources jar) binds down to `"e"`, Ctrl-E, and terminfo
`key_down`; up to `"y"`, Ctrl-Y, `key_up`. `j`/`k` are plain INSERT
(jump-to-item-key for `ChoiceItem`s), not navigation. Also answered the
proponent's open question: ESC **is** bound to CANCEL (returns null/empty
result). Consequence worth knowing: arrow navigation depends on the terminal
honouring keypad_xmit (application-mode `ESC O B`); a terminal sending
normal-mode `ESC [ B` hits the ESC→CANCEL binding plus stray inserts
(observed in the spike before switching to application-mode sequences).
Navigation hints shown to users must say arrows (not j/k), and the terminal
matrix (tmux, various emulators, Windows) needs interactive testing.

**AMENDED — "wizard = sequential batching only; back-navigation hand-rolled".**
Undersold. `ConsolePrompt.prompt(List<AttributedString>, Function<Map<...>, List<PromptableElementIF>>)`
(3.30.15 `ConsolePrompt.java:169-210`) is a multi-step engine: the function
receives interim results and returns the next prompt list (or null to
finish) — conditional steps — and cancelled prompts automatically pop back to
the previous step (`prevLists`/`prevResults` deques). The shell's 3-question
wizard may need no hand-rolled state machine at all.

**AMENDED — "JLine 4.x needs Java 22 / jdk11 classifier on 21".** The precise
version claim holds: 4.3.1 base classes are class-file major 55 (Java 11)
while `org/jline/terminal/impl/ffm/*` are major 66 (Java 22) — verified by
inspecting the default jar — and the jdk11 classifier exists. But the implied
"4.x unusable on 21" is too strong: the spike compiled and ran the console-ui
list prompt on 4.3.1 under JVM 21 successfully (the FFM provider is skipped
at runtime). Staying on 3.30.x remains the conservative call, aligning with
the existing runner pin; it is a preference, not a hard constraint.

### Spike results (scala-cli 1.14.0, Scala 3.8.4, JVM 21, Linux)

Spike source: scratchpad `spike/ConsoleUiSpike.scala` —
`//> using dep org.jline:jline:3.30.15` +
`org.jline:jline-console-ui:3.30.15,exclude=org.jline%jline-builtins`.

- **Resolves + compiles clean**: only the two jars downloaded (exclusion
  effective, no jline-builtins), no interop friction; the Java builder chain
  (`createListPrompt().name(...).message(...).newItem(...).text(...).add()...addPrompt()`
  then `prompt(builder.build())` → `java.util.Map[String, PromptResultItemIF]`
  / `ListResult.getSelectedId`) is fine from Scala 3. API caveat: result
  accessors are `getResult`-style (`InputResult` has no `getInput`).
- **Works end-to-end in a pty**: ran under `script`-allocated pty feeding
  keystrokes; `e e ENTER` and application-mode down-arrow (`ESC O B`) both
  moved selection to item 3 and returned the right id. Same behaviour on
  4.3.1/JVM 21.
- **FAILURE FOUND — non-tty stdin NPEs**: `scala-cli run ... < /dev/null`
  (dumb-terminal path) throws
  `NullPointerException: ... ListChoicePrompt$Operation.ordinal() because "op" is null`
  at `AbstractPrompt.java:878` — on EOF `readBinding` returns null and the
  switch NPEs. ConsoleUI has **no graceful dumb-terminal degradation**. The
  shell must gate interactive prompts on a real tty (e.g.
  `System.console() != null`, as `TerminalInteraction.defaultUseColor`
  already does) and fall back to numbered-menu `readLine` otherwise.
- Not tested (needs a human): real interactive Ctrl-C, terminals that ignore
  keypad_xmit, spawning a claude/codex TUI between prompts.

### Frame challenge: is a select-list library warranted at all?

The shell needs ~5 menu items and a 3-question wizard. A numbered-menu
`readLine` loop on the already-pinned JLine reader is zero new dependencies,
immune to every quirk above (EOF NPE, keypad-mode arrows, e/y bindings), works
over ssh/dumb terminals/redirected stdin, and is ~30 lines. That is the
honest floor, and the EOF NPE means a variant of it is needed anyway as the
non-tty fallback. Against that: the user explicitly wants features out of the
box, ConsoleUI costs 73 KB on an already-present stack, and it brings arrow
menus, checkbox, confirm, masked input, and a back-navigating wizard engine
for free. Verdict: adopting ConsoleUI is justified, but the numbered-menu
fallback is a **requirement** (degradation path), not a discarded
alternative.

### Final ranking

1. **JLine 3.30.15 + jline-console-ui 3.30.15 — UPHELD**, with conditions:
   (a) tty-gate every ConsoleUI prompt and ship the numbered-menu fallback
   (EOF NPE is real, reproduced); (b) document navigation as arrows/e/y — no
   j/k; (c) use the `Function`-based `prompt` overload for the wizard before
   hand-rolling any state machine; (d) interactive terminal-matrix pass
   (tmux/emulators/Windows, Ctrl-C, TUI handoff) before the ADR is finalised.
2. **cue4s — runner-up confirmed**, and the rejection is now source-verified,
   not hearsay: session-scoped raw mode, POSIX Ctrl-C death (ISIG left on),
   native `getchar` thread, plus a VTIME/VMIN index bug. Recheck at ~1.0.
3. Everything else as the proponent ranked it; no re-ordering warranted.

The proponent's overall recommendation survives the attack; two capability
claims were corrected (j/k does not exist; the wizard engine does), one new
defect was found (non-tty NPE), and one constraint was softened (JLine 4 on
JVM 21 works in practice).

## Addendum: view & edit a flow (post-review addition)

Research for two new menu items — "view a flow" (print/page a `.sc` script
with Scala highlighting) and "edit a flow" (open in the user's editor).
Verified against Maven Central, the `jline-3.30.15` tag, git/gh sources, and
the scopatz/nanorc repo, 2026-07-18.

### 1. Syntax highlighting: already on the classpath

**The `jline-builtins` exclusion decision does not flip — it becomes moot.**
The bundle jar `org.jline:jline` already *contains* `org/jline/builtins/`
(noted in §Candidates above); verified for 3.30.15: the bundle jar includes
`org/jline/builtins/SyntaxHighlighter.class`, `Less.class`, `Nano.class`,
`Commands.class`. So the highlighter and pager cost **zero new bytes** — they
arrive with the already-planned 3.28.0 → 3.30.15 bump, and the console-ui
exclusion of the *modular* `jline-builtins` stays exactly as recommended
(it exists to prevent bundle-vs-modular duplicate classes, not to drop the
functionality). For the record, the modular jar it excludes:
`jline-builtins-3.30.15.jar` is 329,379 bytes; POM compile deps are
`jline-reader`, `jline-style` (both in the bundle) and
`com.googlecode.juniversalchardet:juniversalchardet` 1.0.3 (220,813 bytes).
juniversalchardet is referenced only from `Nano$Buffer` (encoding detection in
the nano *editor*) — verified by scanning every class in the jar —  and the
bundle POM marks it optional, so `SyntaxHighlighter` and `Less` work from the
bundle without it.

**`SyntaxHighlighter` API** (read at tag `jline-3.30.15`,
`builtins/src/main/java/org/jline/builtins/SyntaxHighlighter.java`):

- `public static SyntaxHighlighter build(Path nanorc, String syntaxName)` —
  nanorc *config* file (`jnanorc` with `include`/`theme` lines) + syntax name.
- `public static SyntaxHighlighter build(String nanorcUrl)` — parses a
  **single nanorc syntax file** from a URL, with explicit `classpath:` support
  (`"classpath:/nano/scala.nanorc"` → `ClasspathResourceUtil` /
  `Source.ResourceSource`). No config file, no disk extraction — ideal for a
  jar-resource syntax.
- `public AttributedString highlight(String string)` (also
  `AttributedString`/`AttributedStringBuilder` overloads) — per line; render
  via `attributedString.toAnsi(terminal)` or `println(terminal)`. Stateful
  across lines for `start=".." end=".."` block rules; call `reset()` before
  each file.
- Failure mode to note: `build(...)` swallows `IOException` (returns a
  no-rule highlighter that passes text through) — a missing resource degrades
  to plain text, silently. Fine for us.

**No reusable `scala.nanorc` we can bundle.** GNU nano ships no Scala syntax;
the jline3 repo has only demo/test nanorc files (java, json, xml, groovy, gron
— `demo/src/main/scripts/nanorc/`, none for Scala); the de-facto collection
scopatz/nanorc *has* `scala.nanorc` (~12 rules) but the repo is **GPL-3.0**
(its `license` file: "GNU General Public License … version 3 or later") —
not bundleable into Apache-2.0 orca as a resource. Writing our own is the
cheap path: the scopatz file demonstrates the needed size (~12 `color` lines:
keywords, types, `def`/`val`/`var`, strings, `//` + `/* */` comments, plus one
orca-specific rule for `//>` directives). ~20 lines, `.sc|.scala` header
regex, ship as a shell-jar resource.

**`Less` pager** (same tag, `Less.java`, `Commands.java`):

- `public Less(Terminal terminal, Path currentDir, Options opts, ConfigurationPath configPath)`
  (+ 2-arg and 3-arg overloads); `public void run(Source... sources)` /
  `run(List<Source>)` with `Source.PathSource(Path, name)` /
  `URLSource` / `InputStreamSource`. Runs on the caller's thread on an
  **existing Terminal**; full less UI (search, `-F --quit-if-one-screen`,
  `--LINE-NUMBERS`, `-Y --syntax=name`). `Commands.less(terminal, in, out,
  err, currentDir, argv[, configPath])` is the argv-style wrapper.
- Syntax support: yes, nanorc-driven — it reads a `jlessrc` config via
  `ConfigurationPath(appConfig, userConfig)` (`include`/`theme`/`set` lines)
  or, absent config, globs `/usr/share/nano/*.nanorc`; highlighting is built
  internally via `SyntaxHighlighter.build(syntaxFiles, name, syntaxName)`.
  **No injection seam for an in-memory highlighter** — Less wants real nanorc
  *files on disk*, so using it means extracting `jlessrc` + `scala.nanorc` to
  the shell cache (same machinery topic 4 already uses for built-in flows)
  and passing `new ConfigurationPath(cacheDir, cacheDir)`. ~30–50 lines.
- The `/usr/share/nano` fallback won't help: no scala.nanorc there.

**Alternatives weighed**: delegating to `bat`/`less -R` is zero-dep but
non-uniform (`bat` not guaranteed installed; pre-highlighted ANSI into
`less -R` still needs our own highlighter anyway). A hand-rolled fansi regex
highlighter re-implements what the bundle already ships. And for the actual
content — flows are ~100–300 lines — a pager is a nicety, not a need:
printing into scrollback and redrawing the menu is what `git log | cat`-style
users expect, and it avoids the Less/ConfigurationPath extraction entirely in
v1.

### 2. Default-editor launch

**Resolution precedents** (source-verified):

- git (`editor.c`, `git var GIT_EDITOR`): `GIT_EDITOR` > `core.editor` >
  `VISUAL` > `EDITOR` > compiled fallback `vi`; on a dumb terminal `VISUAL`
  is skipped (historical meaning: visual editors need a real terminal) and an
  unset `EDITOR` is an error ("Terminal is dumb, but EDITOR unset").
- gh (`pkg/cmd/root/help_topic.go` env docs): `GH_EDITOR`, `GIT_EDITOR`,
  `VISUAL`, `EDITOR` "in order of precedence", with the `gh config` `editor`
  key consulted first.

**Argument-carrying editor values** (`EDITOR="code --wait"`) are normal and
must be honored:

- git: `launch_specified_editor` runs the editor through `run_command` with
  `use_shell = 1` — the command is wrapped as
  `sh -c '<editor> "$@"' <editor> <path>` whenever it contains shell
  metacharacters (space included), else exec'd directly. The shell sees the
  user's string verbatim, so quotes/vars inside `EDITOR` work.
- gh (`pkg/surveyext/editor_manual.go`): shell-*splits* the command with
  `kballard/go-shellquote` and execs directly (no shell), stdio attached.

Both handle `"code --wait"`; git's trampoline additionally handles quoted
paths and env references with one line of code. Recommend the git shape on
the JVM: `ProcessBuilder("sh", "-c", editor + " \"$@\"", editor,
flowPath.toString).inheritIO()` — unconditionally via `sh` (git's
direct-exec branch is only an optimization). POSIX `sh` is a given on the
shell's target platforms; Windows would need `cmd /c` if ever supported.

**Orca rule (recommendation)**: `VISUAL` > `EDITOR` > `vi` (POSIX-guaranteed;
`nano` is friendlier but not universally present — if we want it, probe
`nano` on PATH before falling back to `vi`). **No `ORCA_EDITOR`**: git and gh
grew tool-specific overrides because they have their own config systems and
tool-specific editing contexts (commit messages vs. general editing); orca
has neither for this feature, the env var can be added later without breaking
anything, and every extra knob is documentation surface. Not consulting
`core.editor` is deliberate too — a flow script is not a git artifact.

The editor is spawned exactly like flow subprocesses: tty-inheriting
(`inheritIO()`), foreground, defensive terminal-attribute re-assert on return
(the existing post-subprocess obligation in the spike checklist covers this;
kill/Ctrl-C behavior likewise rides the existing subprocess rules). A nonzero
editor exit is a warning line, not an error state.

### 3. Semantics per flow tier

Per 05's model (project `{workDir}/.orca/flows/`, global
`$XDG_CONFIG_HOME/orca/flows/`, built-in extracted to
`~/.cache/orca/shell/<version>/flows/` per topic 4):

- **View: all tiers.** Project/global read the real file; built-in reads the
  extracted cache copy (or the jar resource directly — same bytes).
- **Edit: project/global only, directly.** They are real user files; open
  in place. No cache invalidation needed — the menu re-lists on redraw.
- **Edit on a built-in: never edit the cache copy.** It sits in a
  deletable-by-definition cache dir, and the version-keyed extraction means
  edits silently vanish on the next shell upgrade (and violate "the cache is
  regenerable"). Instead, "edit" on a built-in becomes **"customize this
  built-in"**: prompt for a destination tier (project `.orca/flows/` /
  global `~/.config/orca/flows/`), copy the file (via `OrcaDir.ensureFlows`
  for the project tier — symlink guard applies, per 05 §1), then open the
  copy. If the destination name already exists, skip the copy and open the
  existing file — it already shadows the built-in.
- **Shadowing is the point, not a side effect.** Under 05's precedence
  (project > global > built-in) the copy immediately shadows the built-in,
  and the listing already surfaces it as `[project, shadows built-in]` — the
  user sees exactly what their customization did. This is the same
  copy-to-customize pattern as `.claude/commands/` overriding built-in
  behavior, consistent with 05's chosen precedents.

### Recommendation (view & edit)

1. **View, primary**: `org.jline.builtins.SyntaxHighlighter` from the
   already-present bundle jar (post-3.30.15-bump) + an orca-authored ~20-line
   `scala.nanorc` shell-jar resource, loaded via
   `SyntaxHighlighter.build("classpath:/orca/shell/nanorc/scala.nanorc")`;
   highlight per line, print to the shell terminal, redraw the menu. No
   pager in v1 — flows are 100–300 lines and scrollback suffices.
2. **View, upgrade path (optional, later)**: bundled `org.jline.builtins.Less`
   on the existing Terminal when the file exceeds the terminal height —
   requires extracting `jlessrc` + `scala.nanorc` to the shell cache and a
   `ConfigurationPath`; API confirmed, cost ~30–50 lines. Do not delegate to
   `bat` (not guaranteed installed).
3. **View, fallback**: non-tty/dumb terminal → print the file verbatim, no
   ANSI (same gate as the numbered-menu fallback).
4. **Edit**: resolve `VISUAL` > `EDITOR` > `vi`; spawn
   `sh -c '<editor> "$@"' <editor> <path>` with inherited stdio; re-assert
   terminal attributes on return; no `ORCA_EDITOR`.
5. **Tiers**: view everywhere; edit project/global in place; edit on
   built-in = copy-to-project-or-global first ("customize"), open the copy,
   which shadows the built-in per 05's precedence — desired and already
   labeled by the listing.
6. **License note**: write our own `scala.nanorc`; scopatz/nanorc's is
   GPL-3.0 and not bundleable into Apache-2.0 orca.

Sources: repo1.maven.org (`jline-builtins` 3.30.15 jar/POM, juniversalchardet
1.0.3), jar content listings of `jline-3.30.15.jar` and
`jline-builtins-3.30.15.jar`, jline3 sources at tag `jline-3.30.15`
(`SyntaxHighlighter.java`, `Less.java`, `Commands.java`,
`ConfigurationPath.java`, `Source.java`), git `editor.c` (master),
cli/cli `pkg/cmd/root/help_topic.go` + `pkg/surveyext/editor_manual.go`
(trunk), scopatz/nanorc `scala.nanorc` + `license` file.
