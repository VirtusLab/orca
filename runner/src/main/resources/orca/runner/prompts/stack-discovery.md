You are inspecting a source repository (read-only) to discover how this
project formats, lints, and tests itself. Your output configures automated
gates that run repeatedly, so a wrong command is worse than no command:
propose a command ONLY if files in this repository justify it.

Definitions:
- format: rewrites source files to the project's canonical style.
- lint: a cheap sanity gate that a change is well-formed — typically a
  compile or typecheck that covers test sources WITHOUT executing any
  tests. It must be substantially faster than the test suite. When
  several commands qualify, prefer the cheapest one. Never propose the
  test runner (or a command that runs tests) as lint.
- test: runs the project's test suite.

Procedure:
1. Survey the tree for build definitions, lockfiles, task runners, tool
   configs, and CI workflows (CI shows which commands the project itself
   trusts).
2. Prefer the project's own entry points over reconstructing them: a
   justfile/Makefile recipe, a package.json/composer.json script, a build
   wrapper (./gradlew, ./mill). Emit the entry point (e.g. `just fmt`),
   not the commands it happens to run; with a wrapper present, never emit
   the bare tool.
3. Before proposing any tool, verify it is set up HERE: its config file,
   plugin/dependency declaration, or script entry must be present (with
   the single exception in rule 4). A tool being conventional for this
   ecosystem is NOT evidence.
4. Exception — toolchain-bundled commands: a command distributed as part
   of the toolchain the build file already selects, AND designed to run
   with zero project configuration (typically the toolchain's own
   formatter or built-in compile/typecheck), is evidenced by that build
   file itself — cite it as evidencePath, and state in the note that the
   tool ships with the toolchain (claim this only when it is true of the
   toolchain, not of a dependency). This never covers anything installed
   or enabled per project (a dependency, a build-tool plugin, a separate
   binary): those still need their own declaration or config in this repo.
5. For every command, cite the repo-relative file that justifies it
   (evidencePath) and optionally the key/task/line (evidenceNote)
   (required when rule 4's exception applies). If you cannot cite a
   file, do not propose the command — leave the task unset with a
   one-line reason. An unset task with an accurate reason is a correct,
   complete answer; never guess to fill a slot.
6. A repo with several stacks (e.g. a Rust core and a JS frontend)
   contributes its commands to the relevant tasks.
7. Ignore orca flow scripts (.sc files depending on the `orca` library) —
   they drive this automation and are not part of the project's stack.

Never propose a tool installed or enabled per project merely because it
is usual for this ecosystem. Every command must be traceable to this
repository's files — for a toolchain-bundled, zero-config command, the
build file that selects the toolchain is that trace.

The example below uses a FICTIONAL build tool, only to show the output
shape and the lint-vs-test distinction — derive real values from the
repository. Per task, always emit both fields: a task with commands has
"unsetReason": null; an unset task has "commands": [] and a one-line
reason. Likewise every command carries "evidenceNote" — null when you
have nothing to add beyond the evidence file itself:

{"format": {"commands": [{"command": "acme style --write",
    "evidencePath": "acme.build",
    "evidenceNote": "style ships with the acme toolchain, zero-config; CI also runs it in .ci/check.yml line 12"}],
  "unsetReason": null},
 "lint":   {"commands": [{"command": "acme compile --include-tests",
    "evidencePath": "acme.build",
    "evidenceNote": "compiles main and test sources, executes nothing"}],
  "unsetReason": null},
 "test":   {"commands": [], "unsetReason": "no test directory or CI test step found"}}
