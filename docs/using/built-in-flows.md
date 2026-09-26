# Built-in flows

Orca ships these flows. `orca list` shows them together with your project and
global flows; `orca view <flow>` prints the source. Every flow that changes
code opens a PR if `gh` can reach the repository on GitHub. Otherwise it says
so and leaves the committed work on the feature branch.

All of them take the [role agents](../glossary/users.md#agents-and-conversations)
from [settings](settings.md) and need those agents logged in. `gh` is optional
unless noted.

| Flow | Prompt | Does |
|---|---|---|
| `implement.sc` | what to build | Plans the prompt into tasks. Implements each task on the run's branch and reviews it once. Then runs a review-and-fix loop over the whole change. The default choice. |
| `implement-interactive.sc` | what to build | Same as `implement.sc`, but the planner can ask you clarifying questions before producing the plan. On a re-run a finished planning stage is skipped, so you are not asked again. |
| `implement-enhanced.sc` | what to build | `implement.sc` plus two steps: the planner critiques and improves its own draft, and a documentation stage updates the project's docs from what the tasks changed. |
| `simple.sc` | one well-scoped task | No planning. The prompt is the one task, handed straight to the coder, then reviewed. For small changes where a plan is overhead. Also what `orca create` and `orca fork` run. |
| `issue-pr.sc` | `owner/repo#N` or an issue URL | Reads the issue and checks it against the repository: are its claims right, is detail missing, is it a duplicate, is the scope sane. Then either posts a rejection comment or plans, implements, reviews and opens a PR. Branch `fix/issue-<n>`. Needs `gh`. |
| `issue-pr-bugfix.sc` | `owner/repo#N` or an issue URL | The bug-report variant. Triages first: not a bug (comments), a bug no test can show (comments with reproduction steps), or a testable bug. For a testable bug it writes a failing test, opens a tentative PR, waits for CI to go red, confirms the failure matches the report, then fixes and updates the PR. Needs `gh`. |
| `review.sc` | a PR reference or URL, a branch, "the uncommitted changes", a commit range, or a diff on stdin | Review only: picks reviewers, runs them concurrently, prints every finding. When the target is a PR, posts the report on it; a re-run replaces the earlier report. Nothing is fixed or committed. |

Examples:

```bash
orca run implement.sc "Add a multiply function to the calculator crate"
orca run implement-interactive.sc "Add a new arithmetic operation. Ask the user which."
orca run issue-pr.sc "acme/widgets#42"
orca run review.sc "acme/widgets#42"
git diff | orca run review.sc
```

Re-run with the same prompt: the progress log, and for the issue flows the
branch name and the marker that identifies their comment, are derived from it.

## Runnable examples

Two self-contained examples under
[`examples/runnable/`](https://github.com/VirtusLab/orca/tree/master/examples/runnable)
seed a small Rust project into a temp directory and run a flow against it:

- `01-simple`: autonomous planning, then implement and review each task.
- `02-interactive`: the same shape, but the planner can pause to ask you
  questions.

Each has a `create-test-project.sh` and a README with the exact commands. They
need `cargo` on `PATH`.
