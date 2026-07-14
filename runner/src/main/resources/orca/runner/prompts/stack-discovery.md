You are inspecting a source repository (read-only) to discover how this
project formats, lints, and tests itself. Your output configures automated
gates that run repeatedly, so a wrong command is worse than no command:
propose a command ONLY if files in this repository justify it.

Definitions:
- format: rewrites source files to the project's canonical style.
- lint: a cheap sanity gate that a change is well-formed — typically a
  compile or typecheck that covers test sources WITHOUT executing any
  tests. It must be substantially faster than the test suite. Never
  propose the test runner (or a command that runs tests) as lint.
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
   plugin/dependency declaration, or script entry must be present. A tool
   being conventional for this ecosystem is NOT evidence.
4. For every command, cite the repo-relative file that justifies it
   (evidencePath) and optionally the key/task/line (evidenceNote). If you
   cannot cite a file, do not propose the command — leave the task unset
   with a one-line reason. An unset task with an accurate reason is a
   correct, complete answer; never guess to fill a slot.
5. A repo with several stacks (e.g. a Rust core and a JS frontend)
   contributes each stack's commands to each task.
6. Ignore orca flow scripts (.sc files depending on the `orca` library) —
   they drive this automation and are not part of the project's stack.

Never propose a command because it is the usual one for a build tool you
recognized. Every command must be traceable to this repository's files.

The example below uses a FICTIONAL build tool, only to show the output
shape and the lint-vs-test distinction — derive real values from the
repository:

{"format": {"commands": [{"command": "acme style --write",
    "evidencePath": "style.acme",
    "evidenceNote": "also run by CI in .ci/check.yml line 12"}]},
 "lint":   {"commands": [{"command": "acme compile --include-tests",
    "evidencePath": "acme.build",
    "evidenceNote": "compiles main and test sources, executes nothing"}]},
 "test":   {"unsetReason": "no test directory or CI test step found"}}
