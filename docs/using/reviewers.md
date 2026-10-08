# Custom reviewers

A reviewer is a prompt that says what to look for, paired with a read-only
agent. Orca ships nine of them: code-functionality, test, readability,
code-structure, single-source, simplicity, performance, security and scala-fp. You can add
your own, or retune a shipped one, by writing a Markdown file; no code changes
are needed.

## Where reviewers come from

Reviewers are collected from three tiers, read once per
[attempt](../glossary/users.md#flows-and-runs), before Orca changes anything in
the repository:

| Tier | Location | Scope |
|---|---|---|
| project | `.orca/reviewers/*.md` | committed with the repository |
| global | `~/.config/orca/reviewers/*.md` (`$XDG_CONFIG_HOME/orca/reviewers/`) | your own, in every project |
| built-in | shipped with Orca | the nine above |

The tiers merge into one **catalog**. A reviewer's name is its filename stem,
compared case-insensitively, so `.orca/reviewers/orca.md` is the reviewer
`orca`. When two tiers provide the same name, project beats global, and global
beats built-in: the file replaces the reviewer from the lower tier, in the same
position in the catalog. New names are appended, sorted by name.

Flows take reviewers from the catalog through two rosters: `allReviewers`,
which holds every reviewer, and `minimalReviewers`, which holds
code-functionality, readability and test. A new name joins both rosters. A
shadowing file, on the other hand, runs only where the shipped reviewer runs.
For example, `scala-fp` is not in `minimalReviewers`, so
`.orca/reviewers/scala-fp.md` retunes it for this project without changing that
roster. Note that the [reviewer picker](../authoring/review.md) still chooses
from the roster per task.

At the start of a run, Orca lists what the project and global tiers
contributed:

```text
discovered reviewers: orca (project); scala-fp (project, shadows built-in)
```

## File format

A reviewer file is frontmatter followed by a body, the same shape the shipped
reviewers use:

```markdown
---
description: Checks the project's own layering rules.
files: \.scala$
---

## Scope

Review only the layering of the changed files...
```

- `description:` is required and must be a single line. The reviewer picker
  uses it to choose reviewers for a task. A YAML block scalar (`>`, `|`, `>-`,
  `|-`), or a value wrapped onto the next line, aborts the run.
- `files:` is optional. It is a regex matched against each changed path: the
  reviewer is only offered to the picker when the change touches a matching
  file, unless nothing is known about the change set. Of the shipped
  reviewers, only `scala-fp` declares one.
- The body is the reviewer's system prompt.
- A `name:` key is ignored.

## Validation

`README.md` and any file whose name starts with `_` are treated as documents
and skipped. Every other `.md` file must be a valid reviewer. Any of the
following aborts the run before Orca changes the repository, and all bad files
are reported at once:

- a missing or unterminated frontmatter block
- a missing `description:`
- an empty body
- an invalid `files:` regex
- two files claiming one name

Orca fails hard here because a silently dropped reviewer would look like a
clean review.

```{note}
A symlink in `.orca/reviewers/` also aborts the run, since that directory comes
from a repository Orca did not write. Symlinks in the global tier are allowed,
as that is your own configuration.
```

## Using reviewers from a flow

Within a flow, the reviewers are available as `allReviewers(agent)`,
`minimalReviewers(agent)` and `reviewerCatalog`. All three are described in
[Review and fix loops](../authoring/review.md#rosters).
