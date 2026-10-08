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

## `quick.sc`

No planning: the prompt is the one task, handed straight to the coder and then
reviewed in a loop. For small, well-scoped changes, where a plan would be
overhead. It is also the flow that `orca create` and `orca fork` run.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/quick.sc).

## `implement.sc`

The default choice. It plans the prompt into tasks, implements each task on the
run's branch and reviews it once, then runs a review-and-fix loop over the
whole change.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/implement.sc).

## `epics.sc`

For a change too large for one plan. The planner splits the prompt into epics
and critiques that outline. Each epic is planned into tasks just before it
runs, so it builds on the code earlier epics produced; its tasks are reviewed
once each, and the epic as a whole in a loop. A documentation stage and a final
review over the whole change follow.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/epics.sc).

## `resolve.sc`

You give it a request — a bug report, a feature request, any change — or a
GitHub issue as `owner/repo#N` or a URL. Triage checks the request against the
repository and either rejects it (the reply goes on the issue, or is printed)
or accepts it. For a bug a test can show, the flow first writes a failing test
and checks it fails the way the request says. A bug no test can show is fixed
anyway, and the PR says so. Then it plans, implements and reviews like
`implement.sc`. For an issue, the branch is `fix/issue-<n>` and the PR closes
the issue; `gh` is needed then.
[Source](https://github.com/VirtusLab/orca/blob/master/flows/resolve.sc).

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
orca run epics.sc "Add user accounts: storage, sign-up, login"
orca run resolve.sc "acme/widgets#42"
orca run resolve.sc "Dividing by zero crashes the calculator"
orca run review.sc "acme/widgets#42"
git diff | orca run review.sc
```

To resume a run, re-run it with the same prompt: the progress log, and for
`resolve.sc` on an issue also the branch name and the marker that identifies
its comment, are derived from it.

## Runnable examples

A self-contained example under
[`examples/runnable/`](https://github.com/VirtusLab/orca/tree/master/examples/runnable)
seeds a small Rust project into a temporary directory and runs a flow against
it: `01-simple` runs autonomous planning, then implements and reviews each
task. It comes with a `create-test-project.sh` script and a README with the
exact commands to run, and needs `cargo` on `PATH`.
