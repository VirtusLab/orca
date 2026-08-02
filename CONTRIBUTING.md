# Contributing to Orca

How to build, test, and run a locally modified Orca. Internals, architecture,
and coding conventions live in [AGENTS.md](AGENTS.md); end-user documentation
in the [README](README.md). **sbt 1.12+** is needed in addition to the
runtime requirements listed in the README.

## Build and test

```bash
sbt compile                             # build every module
sbt test                                # unit tests across all modules
sbt "flow/test"                         # scope to one module
sbt "flow/testOnly orca.FixLoopTest"    # scope to one suite
sbt scalafmtAll                         # reformat every source in place
sbt scalafmtCheckAll                    # fail if anything would reformat
```

Extra Scala 3 warnings are enabled (`-Wunused:all`, `-Wvalue-discard`,
`-Wnonunit-statement`). They aren't fatal — fix them before committing
rather than relying on the compiler to block. `sbt ~test` re-runs tests on
save.

The build also loads from a linked `git worktree`: `build.sbt` enables
sbt-git's `useReadableConsoleGit`, without which project load dies in JGit
with `NoWorkTreeException`. Don't remove it. In exchange, project load now
needs a usable `git` on the PATH — with git missing, or refusing the
repository (a `safe.directory` complaint under a bind mount, say), sbt fails
to load rather than carrying on with a fallback version.

## Integration tests (gated)

Some tests shell out to real external tools and skip by default:

```bash
ORCA_INTEGRATION=1 sbt test
ORCA_INTEGRATION=1 sbt "claude/testOnly orca.tools.claude.ClaudeIntegrationTest"
ORCA_INTEGRATION=1 sbt "tools/testOnly orca.tools.OsGitHubIntegrationTest"
ORCA_INTEGRATION=1 sbt publishLocal "shell/testOnly *BuiltInFlowsCompileTest"
ORCA_INTEGRATION=1 sbt publishLocal "runner/testOnly *ScalaCliSmokeTest"
```

| Suite | Needs |
|---|---|
| `{Claude,Codex,Gemini,Opencode,Pi}IntegrationTest` (one per `orca.tools.<backend>`) | that backend's CLI authenticated |
| `OsGitHubIntegrationTest` | `gh` authenticated |
| `BuiltInFlowsCompileTest`, `FlowAuthoringSmokeTest` | `scala-cli`, and a `publishLocal` in the same sbt invocation |
| `ScalaCliSmokeTest` | the above, plus `claude` authenticated — it starts a real flow |

The three scala-cli suites link a script against the local Ivy cache, so they
need `publishLocal` in the *same* sbt invocation: the build injects that run's
dynver version as `-Dorca.build.version` into the forked test JVM, which is what
the scripts pin.

`BuiltInFlowsCompileTest` is the only one CI runs (its own `flow-scripts` job),
being the only one needing no credentials. It compiles every built-in flow as
`BuiltInFlows` stages them for a dev build — pin rewritten to the just-published
version, `//> using repository ivy2Local` inserted — so an API change that
breaks the flows fails CI instead of shipping. On a release version there is no
rewrite (the flows resolve from Maven Central), so it skips itself.

## Publishing locally

```bash
sbt publishLocal
```

Installs `org.virtuslab::orca:0.0.17` plus its transitive modules
(`orca-tools`, `orca-flow`, and the five backends
`orca-{claude,codex,gemini,opencode,pi}`) into `~/.ivy2/local` so a flow
script with `//> using repository ivy2Local` can resolve them. For an
iteration loop, `sbt "~publishLocal"` rebuilds and republishes on every
save.

## Testing the `orca` CLI with local changes

After `sbt publishLocal`, run the shell from the local artifact instead of
the released one. Isolate the config and cache dirs so your real
`~/.config/orca` and wizard state stay untouched; `--workspace` keeps
scala-cli's own `.scala-build`/`.bsp` for this no-input `--dep`+`--main-class`
invocation out of the scratch project, the same fix the `orca` shim applies
(`install.sh`).

```bash
version="$(sbt -batch -error "print shell/version" | tail -1)"
mkdir -p /tmp/orca-dev/project /tmp/orca-dev/xdg/{config,cache} /tmp/orca-dev/workspace
cd /tmp/orca-dev/project
git init -q 2>/dev/null; git commit -q --allow-empty -m scratch 2>/dev/null; true

XDG_CONFIG_HOME=/tmp/orca-dev/xdg/config XDG_CACHE_HOME=/tmp/orca-dev/xdg/cache \
  scala-cli run --workspace /tmp/orca-dev/workspace --jvm 21 --quiet \
    --dep "org.virtuslab::orca-shell:$version" \
    --repository ivy2local \
    --main-class orca.shell.Main
```

That starts the interactive shell (first run goes through the wizard). For
the headless CLI, append the subcommand after `--`:

```bash
XDG_CONFIG_HOME=... XDG_CACHE_HOME=... \
  scala-cli run --workspace /tmp/orca-dev/workspace --jvm 21 --quiet \
    --dep "org.virtuslab::orca-shell:$version" \
    --repository ivy2local \
    --main-class orca.shell.Main -- run implement.sc "your task"
```

A dev (non-release) version automatically rewrites the built-in flows' orca
dep pin to `$version` and adds `ivy2Local`, so the flows a run launches also
resolve your local build. The initial empty commit matters: some features
(e.g. committing an authored flow) degrade gracefully on a repo with no
commits. Drop the `XDG_*` overrides to test against your real configuration
instead; state persists across runs either way.
