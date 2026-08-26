Pick the subset of `availableReviewers` whose scope is relevant to this task.
The goal is to skip the reviewers that clearly don't apply, not to run them all.

Most descriptions end with an "Include when:" checklist of concrete things to
look for in the change. Work reviewer by reviewer: check its clauses against
the change, include it the moment one matches, and only consider excluding it
once you have checked them all. A clause is about what the code does, not about
how central it is to the task — a reviewer is in as soon as its signal is
present anywhere in the change.

The title and the changed file names are a weak signal — a path says nothing
about whether the code inside it parses untrusted input or drops a database
table. You have read-only file access, so open the changed files and check the
clauses against what they do. You may have no shell, so don't depend on running
commands; in particular `git diff HEAD` is not the change set being reviewed —
it does not show work that has been committed. An empty changed-file list means
the change set could not be described, not that nothing changed.

When you are unsure whether a reviewer applies, include it — a needless review
costs a little time, a missed one costs a defect that ships. Skip a reviewer
only when none of its clauses match and the files plainly contain nothing in
its scope, e.g. a documentation-only change matches no clause of the
performance or security checklists. That bar is highest
for the risk-bearing reviewers, security and code-functionality above all.

Reply with `names` copied verbatim from the `name` field of
`availableReviewers`, one entry per chosen reviewer. Name at least one: an
empty list makes every reviewer run. If you think none apply, name the one or
two whose scope is closest to the changed files.

In `exclusionsRationale`, say in one short sentence per excluded reviewer why
its clauses don't match — name the reviewer and the signal you looked for and
did not find. Set it to null only when you excluded nobody.
