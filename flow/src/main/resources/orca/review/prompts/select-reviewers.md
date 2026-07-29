Pick the subset of `availableReviewers` whose scope is relevant to this task.
The goal is to skip the reviewers that clearly don't apply, not to run them all.

The title and the changed file names are a weak signal — a path says nothing
about whether the code inside it parses untrusted input or drops a database
table. You have read-only file access, so open the changed files and see what
they do before excluding a reviewer. You may have no shell, so don't depend on
running commands; if `git diff HEAD` does work, treat its output as the better
evidence, and if it comes back empty treat the change as unknown rather than
as an empty one.

When you are unsure whether a reviewer applies, include it — a needless review
costs a little time, a missed one costs a defect that ships. Skip a reviewer
only when the files plainly contain nothing in its scope, e.g. a
pure-documentation change with no executable code. That bar is highest for the
risk-bearing reviewers, security and code-functionality above all.

Reply with a SelectedReviewers whose `names` are copied verbatim from the `name`
field of `availableReviewers` (one entry per chosen reviewer). When some
reviewers apply, don't return an empty list.
