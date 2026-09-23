Pick the subset of the available reviewers whose scope is relevant to this
task. The goal is to skip the reviewers that clearly don't apply, not to run
them all.

The title and the changed file names are a weak signal — a path says nothing
about whether the code inside it parses untrusted input or drops a database
table. You have read-only file access, so open the changed files and see what
they do before excluding a reviewer. You may have no shell, so don't depend on
running commands; in particular `git diff HEAD` is not the change set being
reviewed — it does not show work that has been committed. An empty changed-file
list means the change set could not be described, not that nothing changed.

When you are unsure whether a reviewer applies, include it — a needless review
costs a little time, a missed one costs a defect that ships. Skip a reviewer
only when the files plainly contain nothing in its scope, e.g. a change that
touches no test file has nothing for the test reviewer. That bar is highest
for the risk-bearing reviewers, security and code-functionality above all.
If you think none apply, pick the one or two whose scope is closest to the
changed files.
