# Development

How Orca itself is built. The full material lives in the repository:
[CONTRIBUTING.md](https://github.com/VirtusLab/orca/blob/master/CONTRIBUTING.md)
for build, test and local-run recipes,
[AGENTS.md](https://github.com/VirtusLab/orca/blob/master/AGENTS.md) for
internals and coding conventions, and
[`adr/`](https://github.com/VirtusLab/orca/tree/master/adr) for the
architecture decision records.

## Layout

Orca is Scala 3 on [Ox](https://ox.softwaremill.com/) for structured
concurrency, [tapir](https://tapir.softwaremill.com/) for JSON Schema
derivation and [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala)
for codecs.

```
orca/
├── tools/      # tool traits + os-lib-backed impls (git/gh/fs), LLM SPI, session durability, events
├── flow/       # stage/display/fail, FlowContext/FlowControl; orca.{plan,review,pr,progress}
├── claude/ codex/ gemini/ opencode/ pi/   # one module per coding-agent backend
├── runner/     # flow() entry, default wiring, the run lifecycle, terminal UI
├── shell/      # orca-shell: the `orca` CLI
├── flows/      # the built-in flow scripts, bundled into the shell
└── docs/       # this site
```

```
tools   (standalone)
  ├── flow                          → tools
  ├── claude / codex / gemini /
  │     opencode / pi               → tools
  ├── runner                        → tools + flow + all five backends   (published as `orca`)
  └── shell                         → runner                             (published as `orca-shell`)
```

The user-facing surface is `package orca`: `flow`, the tool and role-agent
accessors, `stage`/`display`/`fail`, `JsonData`, `OrcaArgs`. The flow module
adds `orca.plan`, `orca.review`, `orca.pr`. The stage-bound runtime is
specified in [ADR
0018](https://github.com/VirtusLab/orca/blob/master/adr/0018-stage-bound-flow-runtime.md);
read it before touching `stage`, the progress log or sessions.

## Build and test

Requires sbt 1.12+ and JDK 21.

```bash
sbt compile               # every module
sbt test                  # unit tests; no network, no real CLIs
sbt "flow/testOnly *LintTest"   # one suite
sbt scalafmtAll
sbt publishLocal          # into ~/.ivy2/local, for flows using `//> using repository ivy2Local`
```

Integration suites that shell out to real CLIs are gated behind
`ORCA_INTEGRATION=1`. CI runs the unit tests and compiles every built-in flow
against a locally published build, so an API change that breaks a built-in flow
fails CI. CONTRIBUTING.md has the recipe for running a locally built `orca` shell
against a scratch project.

## Documentation

This site is built with Sphinx from `docs/`; its
[README](https://github.com/VirtusLab/orca/blob/master/docs/README.md) says how
to run it locally. The release process bumps the Orca version in every doc
snippet.
