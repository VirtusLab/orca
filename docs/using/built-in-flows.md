# Built-in flows

Orca ships with the flows described below. `orca list` shows them together
with your project and global flows, and `orca view <flow>` prints a flow's
source. Every flow that changes code opens a PR if `gh` can reach the
repository on GitHub; otherwise it says so and leaves the committed work on the
feature branch.

All of the flows take the
[role agents](../glossary/users.md#agents-and-conversations) from
[settings](settings.md), and those agents need to be logged in. `gh` is
optional unless a flow says otherwise.

## `implement.sc`

This is the default choice. You give it a description of what to build; it
plans the prompt into tasks, implements each task on the run's branch and
reviews it once, then runs a review-and-fix loop over the whole change.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/implement.sc).

## `implement-interactive.sc`

The same as `implement.sc`, except that the planner can ask you clarifying
questions before it produces the plan. Note that on a re-run a finished
planning stage is skipped, so you are not asked again.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/implement-interactive.sc).

## `implement-enhanced.sc`

`implement.sc` with two extra steps: the planner critiques and improves its
own draft, and a documentation stage updates the project's docs based on what
the tasks changed.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/implement-enhanced.sc).

## `simple.sc`

There is no planning here: the prompt is the one task, handed straight to the
coder and then reviewed. This is useful for small, well-scoped changes, where a
plan would be overhead. It is also the flow that `orca create` and `orca fork`
run. [Source](https://github.com/VirtusLab/orca/blob/master/flows/simple.sc).

## `issue-pr.sc`

You give it an issue, as `owner/repo#N` or as a URL. The flow reads the issue
and checks it against the repository: are its claims right, is any detail
missing, is it a duplicate, is the scope sane. Depending on the outcome, it
either posts a rejection comment or plans, implements, reviews and opens a PR.
The branch is named `fix/issue-<n>`. This flow needs `gh`.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/issue-pr.sc).

## `issue-pr-bugfix.sc`

The bug-report variant of `issue-pr.sc`, taking the same prompt. It triages
the issue first, with three possible outcomes: it is not a bug (the flow
comments), it is a bug that no test can show (the flow comments with
reproduction steps), or it is a testable bug. For a testable bug, the flow
writes a failing test, opens a tentative PR, waits for CI to go red, confirms
that the failure matches the report, and only then fixes the bug and updates
the PR. This flow needs `gh`.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/issue-pr-bugfix.sc).

## `review.sc`

Review only. The prompt says what to review: a PR reference or URL, a branch,
"the uncommitted changes", a commit range, or a diff on stdin. The flow picks
reviewers, runs them concurrently and prints every finding. When the target is
a PR, it also posts the report on it, and a re-run replaces the earlier report.
Nothing is fixed or committed.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/review.sc).

## Examples

```bash
orca run implement.sc "Add a multiply function to the calculator crate"
orca run implement-interactive.sc "Add a new arithmetic operation. Ask the user which."
orca run issue-pr.sc "acme/widgets#42"
orca run review.sc "acme/widgets#42"
git diff | orca run review.sc
```

To resume a run, re-run it with the same prompt: the progress log, and for the
issue flows also the branch name and the marker that identifies their comment,
are derived from it.

## Runnable examples

Two self-contained examples under
[`examples/runnable/`](https://github.com/VirtusLab/orca/tree/master/examples/runnable)
seed a small Rust project into a temporary directory and run a flow against
it:

- `01-simple` runs autonomous planning, then implements and reviews each task.
- `02-interactive` has the same shape, but the planner can pause to ask you
  questions.

Each example comes with a `create-test-project.sh` script and a README with
the exact commands to run. They need `cargo` on `PATH`.
