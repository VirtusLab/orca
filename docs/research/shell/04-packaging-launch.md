> Final (codebase-factual topic; no adversarial pass). Two cross-topic
> amendments after topics 2/7 resolved: (1) the shell no longer needs
> `runFlow`/exit-free entry — flows run as scala-cli subprocesses (topic 2) —
> so the `runner` dependency is justified by settings machinery, `BackendTag`,
> `OrcaDir` and manifest types instead; (2) the `~/.cache/orca/shell/<ver>/flows/`
> extraction here is for scala-cli to RUN built-ins and is unaffected by topic
> 7's separate rule that material a HARNESS must read gets extracted into the
> harness's workspace.

# 04 — Shell packaging & launch story

Topic 4 of the [research plan](00-research-plan.md). The shell "should be its
own executable, launching scala-cli, where the shell is really implemented",
shipped as an add-on module; direct `scala-cli run flow.sc` must keep working
unchanged.

## 1. Current install/run story (codebase findings)

**How users get orca today.** There is no installer. Orca is a library on
Maven Central (`org.virtuslab::orca`); the *only* prerequisites are a JDK and
scala-cli. Every flow script self-bootstraps via directives
(`examples/implement.sc` and friends all start with):

```scala
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.17"
//> using jvm 21
```

and users run `scala-cli run implement.sc -- "task"`. README's "Getting set
up" section is literally that one command — scala-cli fetches everything.
This is the bar the shell must not regress: **zero install beyond scala-cli**.

**Module layout** (`/home/adamw/orca/build.sbt`): `tools` → backends
(`claude`, `codex`, `opencode`, `pi`, `gemini`) + `flow` → `runner`. The
`runner` module is published as plain **`orca`** ("so flow-script coordinates
stay short") and already depends on `jline`, `fansi`, `mainargs`, `ox`. The
root project aggregates all modules, `publish / skip := true`. No module has
a main class today (`mainargs` is only used for `OrcaArgs` parsing).

**Version at runtime.** There is **no BuildInfo plugin**.
`runner/src/main/scala/orca/runner/OrcaBanner.scala` reads
`getClass.getPackage.getImplementationVersion` (the jar manifest's
`Implementation-Version`, present in published artifacts — the banner shows
real versions in production) and falls back to `"dev"` when running from
class directories. The shell can reuse exactly this pattern.

**Release/version-bumping machinery.**
- `project/UpdateScalaCliVersionInDocs.scala` rewrites two things in `.md` /
  `.sc` files (walking directories recursively): scala-cli coords
  `org.virtuslab::<module>:<version>` and `//> using scala <version>` pins
  (kept equal to the build's `V.scala`, because published TASTy only resolves
  on a compiler at or above the one it was built with).
- Root `updateDocs` (build.sbt) chains the stock sbt-style
  `UpdateVersionInDocs` (README only) with `UpdateScalaCliVersionInDocs` over
  `README.md`, `AGENTS.md`, and **`examples/`** (the whole directory).
- Release flow (sbt-softwaremill-publish's custom `release` command, per its
  [Publish.scala](https://github.com/softwaremill/sbt-softwaremill)): set
  version → run `updateDocs` → `git add` changed files → commit "Release X" →
  tag `vX` → push. Publishing then happens **in CI on the tag**
  (`.github/workflows/ci.yml`: `on.push.tags: [v*]` → `sbt ci-release`,
  dynver-derived version). Consequence: *anything checked into the repo that
  `updateDocs` rewrites is guaranteed version-correct in the tagged tree that
  CI builds artifacts from.* This is the sync lever for built-in flows (§5).

**Examples layout.** `examples/*.sc` — six flow scripts (`implement.sc`,
`implement-interactive.sc`, `implement-enhanced.sc`, `epic.sc`,
`issue-pr.sc`, `issue-pr-bugfix.sc`), each a self-contained scala-cli script
with the three directives + a long doc comment. `examples/runnable/{01-simple,
02-interactive}/` are *seed harnesses*, not flows: each has a
`create-test-project.sh` that copies a small Rust `test-project/` starter into
a temp dir, git-inits it, and copies one of the `examples/*.sc` scripts
alongside (`FLOWS_DIR="$SCRIPT_DIR/../.."` — i.e. `examples/`). Shared logic
lives in `examples/runnable/_seed_lib.sh`: `parse_args`
(`--local`/`--run`/`--settings`), `resolve_dest`, `init_destination`, and
`apply_local_flag`, which runs `sbt publishLocal`, reads the dynver version,
and rewrites the copied script's `using dep` + injects
`//> using repository ivy2Local` — the existing precedent for "run built-in
flows against a dev build". If flows move out of `examples/` (§4), the
harnesses' `FLOWS_DIR` and the `updateDocs` file list must follow.

## 2. What the `orca-shell` executable is — options

Baseline fact (verified empirically with scala-cli 1.14.0): **scala-cli can
run a main class straight from a Maven dependency with no source files at
all** —

```bash
scala-cli run --dep org.scalameta:scalafmt-cli_2.13:3.8.3 \
  --main-class org.scalafmt.cli.Cli -- --version   # → "scalafmt 3.8.3"
```

and `latest.release` resolves too (`--dep …:latest.release` picked 3.11.4).
Nothing is compiled in this mode (no sources → no TASTy concerns); scala-cli
just resolves the classpath via coursier and launches the JVM. So the entire
"executable" can be one exec line.

### 2a. Tiny installed shim (recommended)

A ~5-line POSIX script `orca` (or `orca-shell`):

```bash
#!/usr/bin/env bash
exec scala-cli run --jvm 21 \
  --dep "org.virtuslab::orca-shell:0.0.18" \
  --main-class orca.shell.Main -- "$@"
```

- **Meets the requirement literally**: the executable launches scala-cli,
  where the shell is really implemented (as a published jar).
- **Everything heavy is already solved**: coursier caching, JVM provisioning
  (`--jvm 21` fetches a JDK if absent), artifact resolution — all scala-cli's
  job, same as for flows today. First run downloads; later runs are cached.
- **Install**: (i) a `curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash`
  one-liner that drops the shim into `~/.local/bin` (document `PATH`); the
  shim in-repo gets its pinned version bumped by adding it to the
  `updateDocs` file set (trivially — `UpdateScalaCliVersionInDocs`'s coord
  regex `org::name:ver` matches inside a shell script if we extend the
  extension filter, or the shim can be a `.md`-adjacent template; simplest:
  teach the walker about the one extra file). (ii) Homebrew tap later if
  demand appears — a formula that installs a shell script is trivial, but
  it's maintenance; not needed at launch. (iii) `cs install` — see 2b.
- **Version pinning choice**: a *pinned* shim is deterministic and offline-
  friendly after first fetch, but goes stale — the installed shim keeps
  launching 0.0.18 forever. An *unpinned* shim (`latest.release`, verified to
  work) never goes stale but re-checks Maven metadata (coursier caches
  metadata with a TTL, so it's not per-run network) and makes runs
  non-reproducible. Recommendation: **shim uses `latest.release`; the shell
  itself prints its resolved version at startup** (OrcaBanner pattern) —
  flows stay reproducible regardless because *they* pin their own orca
  version in `using dep`; only the interactive shell floats. Users who want
  a pinned shell can edit one line. This also means install.sh never needs
  version-bumping at all.

### 2b. `cs install` / coursier app descriptor

Mechanics (per [coursier install docs](https://get-coursier.io/docs/cli-install)
and [app descriptors](https://get-coursier.io/docs/cli-appdescriptors)): an
app descriptor is a JSON file — `{"dependencies":
["org.virtuslab::orca-shell:latest.release"], "repositories": ["central"],
"mainClass": "orca.shell.Main", "launcherType": "bootstrap"}` — served from a
*channel*. Channels are (1) JAR-based, published to Maven Central (like
`io.get-coursier:apps`), (2) **URL-based — a JSON file at any public URL**
(e.g. `raw.githubusercontent.com/VirtusLab/orca/master/orca-shell.json`), or
(3) a PR into coursier's default `apps`/`apps-contrib` channels so bare
`cs install orca-shell` works. Launcher types: `bootstrap` (small jar that
resolves deps on first run), `standalone`, `assembly`, `graalvm-native-image`,
`prebuilt`.

The rub: **a coursier bootstrap launches the JVM directly — scala-cli is not
in the loop.** Two readings of "launching scala-cli":

- *Literal/essential*: the shell process must be started by scala-cli.
  Plausible rationale: scala-cli is orca's one guaranteed prerequisite, it is
  what runs flows, and using it for the shell keeps a single toolchain
  (JVM management, caches, `ivy2Local` for dev) with zero new infrastructure.
  Under this reading, `cs install` conflicts and is out (or acceptable only
  as a shim-installer, i.e. `launcherType: prebuilt` pointing at the shim
  script — awkward).
- *Incidental*: "launching scala-cli" just describes the cheapest way to get
  a self-bootstrapping executable; the essential requirements are "own
  executable" + "no new install burden". A coursier bootstrap satisfies both
  — but it *adds* a prerequisite (`cs`) that orca has never required, while
  scala-cli remains required anyway for running flow scripts. So even under
  the permissive reading, cs-install is at best a *secondary* channel for
  users who already live in the `cs` ecosystem.

Either way the conclusion is the same: **don't build the launch story on
coursier channels; optionally add a URL-based channel JSON later** (10 lines,
no infra) for `cs install --channel <url> orca-shell` as a convenience. Do
not block on an `apps-contrib` PR.

### 2c. `scala-cli --power package`

Per [scala-cli package docs](https://scala-cli.virtuslab.org/docs/commands/package):
a `--power` command producing lightweight launcher JARs (default — small,
deps fetched via coursier on first launch), assemblies, GraalVM native
images, Docker images, and OS packages (deb/rpm/pkg/msi). Cons dominate:

- The build is **sbt**, not scala-cli — packaging via scala-cli creates a
  second, parallel build definition to keep in sync.
- Native image: per-OS/per-arch CI matrix, GitHub-release binary hosting,
  and reflection/JNI config for jline + logback (jline uses native terminal
  access) — real, ongoing cost for zero requirement served.
- Any packaged binary bypasses scala-cli at launch, same objection as 2b,
  and still needs scala-cli at runtime for flows.
- A launcher JAR is strictly worse UX than the shim (`java -jar` vs `orca`).

Reject for now; native image is a plausible *future* optimization of shell
startup, not a launch-story foundation.

### 2d. Documented one-liner only

`scala-cli run --jvm 21 --dep org.virtuslab::orca-shell:0.0.18 --main-class
orca.shell.Main` in the README, bumped by `updateDocs` like every other
coordinate. Zero infrastructure, fully consistent with orca's current
"the README is the installer" story — but unmemorable for the *flagship
entry point* whose whole purpose is discoverability for new users. Keep it
**in addition** to the shim (it is also exactly what install.sh writes into
the shim, and what CI/containers can use directly).

## 3. New sbt module `shell`

```scala
lazy val shell = (project in file("shell"))
  .dependsOn(runner, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-shell",                       // org.virtuslab::orca-shell
    Compile / mainClass := Some("orca.shell.Main"),
    libraryDependencies ++= Seq(/* jline-console-ui, per topic 3's outcome */)
  )
```

- **Depends on `runner`** (published `orca`): *[justification superseded —
  see header note (1); `runFlow` is not needed under subprocess execution]*
  it needs `runFlow` (the
  exit-free `private[orca]` entry point — widen to `private[orca]`-friendly
  access or a `orca.shell` opener, per topic 2's outcome), the settings
  machinery for the wizard (topic 6), and it transitively reuses jline/fansi
  already on runner's classpath. Depending on `runner` rather than `flow`
  keeps all five backends available to the shell, which the wizard needs for
  harness auto-detection anyway.
- Add `shell` to the root aggregate. `runner` gains nothing and keeps zero
  knowledge of the shell — the add-on constraint ("direct `scala-cli run
  flow.sc` keeps working") is satisfied structurally: nothing in the
  library path changes.
- Version self-knowledge: manifest `Implementation-Version` à la
  `OrcaBanner.version` (same fallback `"dev"` for sbt-run/tests). Since all
  modules release under one dynver version, **shell version = orca version**
  — the shell at 0.0.18 knows built-in flows must pin orca 0.0.18 without
  any extra plumbing. (Optionally centralize: move the version-reading
  helper somewhere shared instead of duplicating OrcaBanner's line.)

## 4. Built-in flows: where they live and how they ship

**Move `examples/*.sc` → top-level `flows/`** (the plan's suggestion), and
keep `examples/runnable/` as seed harnesses pointing at `../../flows`
(update `FLOWS_DIR` in both `create-test-project.sh` files, the READMEs'
relative links, and swap `file("examples")` → also `file("flows")` in the
root `updateDocs` — `UpdateScalaCliVersionInDocs` walks directories and
rewrites `.sc` files already, so `flows/` is covered by construction.)

Two distribution mechanisms evaluated:

**(A) JAR resources in orca-shell + extraction to a cache dir — recommended.**
- Build: `Compile / resourceGenerators += copy of flows/*.sc into
  orca/shell/flows/` (a resource generator, not `unmanagedResourceDirectories`,
  so the files land under a namespaced prefix and we can also emit an
  **index resource** — `orca/shell/flows/index` listing filenames — because
  jar resources are not listable via `getResource`; an sbt-generated index
  at build time is the standard fix. First-line `//` descriptions for the
  listing UI (topic 5) can be parsed from the extracted files at runtime, or
  baked into the index; runtime parsing is simpler and stays correct.)
- Runtime: scala-cli needs a real file on disk, so extract to
  `$XDG_CACHE_HOME/orca/shell/<version>/flows/` (default
  `~/.cache/orca/shell/…`; note `.orca/cache/` under a *project* is the
  wrong place — built-ins are not project state). Extract-if-absent keyed by
  the version segment; `dev` version always re-extracts (or, in dev, reads
  straight from the repo's `flows/` if present — mirrors `--local`).
- Why it wins: **self-contained and version-consistent by construction** —
  the flows in the jar were rewritten by `updateDocs` in the very commit the
  jar was built from (§5), so the `using dep` pin always matches the shell's
  own version; works offline after first fetch; no tag→URL mapping code.

**(B) Fetch raw from GitHub at the release tag**
(`raw.githubusercontent.com/VirtusLab/orca/v<version>/flows/<name>.sc`).
- Needs network on every cache miss; fails in offline/air-gapped use; needs
  the version→tag convention hardcoded (`v$version` — true today per
  ci.yml, but now load-bearing at runtime); breaks silently for `dev`
  builds (no tag exists); adds an HTTP client + error surface for zero
  functional gain over (A). Its only advantage — flows updatable without a
  release — is an anti-feature here: built-ins should be exactly as tested
  with this release.
- Verdict: **not needed, not even as a fallback.** (A copy-out benefit of
  (A): the extracted cache dir doubles as the user-visible "here are the
  built-in flows to crib from" location, replacing the "browse examples/ on
  GitHub" story.)

"Both" (A with B as fallback) adds B's complexity without a scenario that
reaches the fallback — the jar that is running *contains* the resources.

## 5. Keeping the flows' orca pin in sync with the shell release

Chain that already exists, extended by one directory:

1. `release` (sbt-softwaremill custom command): set version 0.0.18 → run
   `updateDocs` → `UpdateScalaCliVersionInDocs` rewrites
   `//> using dep "org.virtuslab::orca:0.0.18"` and `//> using scala` in
   `README.md`, `AGENTS.md`, `examples/`, **`flows/`** (added) → commit
   "Release 0.0.18" → tag `v0.0.18` → push.
2. CI on the tag runs `sbt ci-release`; dynver derives 0.0.18 from the tag;
   the `shell` module's resource generator copies the *already-rewritten*
   `flows/*.sc` into the jar; sbt stamps `Implementation-Version: 0.0.18`.
3. At runtime, shell reads its manifest version (0.0.18) and extracts flows
   that pin `orca:0.0.18`. Nothing can drift: one commit, one tag, one
   version source.

Edge: `sbt publishLocal` of a dev build (dynver `0.0.18+5-abc123`) embeds
flows still pinning the *last released* orca — the shell should detect a
non-release version (contains `+`, or `"dev"`) and offer the `_seed_lib.sh`
`apply_local_flag` treatment (rewrite pin + `using repository ivy2Local`) or
just warn. Same edge already exists for examples today; not new risk.

## 6. Recommended launch + distribution story

1. New sbt module `shell` → published `org.virtuslab::orca-shell`, main
   class `orca.shell.Main`, depends on `runner`.
2. Executable = **shim script installed by a curl-able `install.sh`** into
   `~/.local/bin/orca`, exec-ing
   `scala-cli run --jvm 21 --dep org.virtuslab::orca-shell:latest.release
   --main-class orca.shell.Main -- "$@"` — literal compliance with
   "launching scala-cli", zero new prerequisites, no version-bump churn in
   the shim itself; the README also documents the raw one-liner (pinned
   form, bumped by `updateDocs`) for CI and the install-averse.
3. Built-in flows move `examples/*.sc` → `flows/`, ship as jar resources
   with a generated index, extracted on demand to
   `~/.cache/orca/shell/<version>/flows/`.
4. Version sync rides the existing release machinery: add `flows/` (and the
   README one-liner) to `updateDocs`; shell self-identifies via manifest
   `Implementation-Version` (OrcaBanner pattern — no BuildInfo plugin
   needed).
5. Later, optional, non-blocking: URL-based coursier channel JSON in-repo
   for `cs install --channel … orca-shell`; native-image packaging only if
   shell startup latency proves to matter.

Sources:
- https://get-coursier.io/docs/cli-install
- https://get-coursier.io/docs/cli-appdescriptors
- https://scala-cli.virtuslab.org/docs/commands/package
- https://scala-cli.virtuslab.org/docs/commands/run/
- Local verification (scala-cli 1.14.0): `scala-cli run --dep <coords>
  --main-class <cls>` with no sources works, including `latest.release`.
