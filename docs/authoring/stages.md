# Stages

`stage(name)(body)` is the committing, resumable unit of work. On success Orca
records the body's result in the progress log and makes one commit: the code
changes plus the log update. On a re-run, a stage that already has a recorded
result is skipped, and the stored result is returned.

```scala
def stage[T: JsonData](name: String, commitMessage: Option[T => String] = None)(body: => T): T
```

The signature is simplified: the body also receives the capability tokens
described in [Capabilities](capabilities.md).

- The result type `T` needs a `JsonData`. `case class Foo(...) derives JsonData`
  is enough; `Unit`, `String` and the library's types have one.
- The commit message defaults to a summary of the diff, written by
  `codingAgent.cheap`. Pass `commitMessage` to override it.
- Stages can nest; the output indents them.
- The stage name appears in the event log, the commit message and the resume
  preamble. Choose one a reader understands without the code: `"Push + open PR"`.

## Side effects happen inside stages

Every side-effecting call must be inside a `stage` body, and the compiler
enforces it. A mutation outside a stage does not compile. This covers
`git.push`, `fs.write`, `gh` writes, and every `agent.*.run`.

These run anywhere:

- pure reads: `git.uncommittedDiff`, `git.changedFiles`, `gh.readIssue`,
  `gh.availability`, `fs.read`
- `display(message)`: progress output only, no stage, no commit, no log entry
- `fail(message)`: abort with a message; the run stays on the feature branch so
  a re-run resumes
- `agent.session(name, seed)`: creating the handle only registers a name;
  running it is the side effect

## Authoring rules

The compiler does not check these. They keep a flow resumable.

1. **Do not stage reads.** A stage with only reads wastes a commit and a
   checkpoint.

2. **Push in a later stage than the edit.** A stage commits only on completion,
   so a `git.push()` in the same stage as the edit pushes nothing. Put the
   push in a separate, later stage. `git.push()` and `gh.createPr` return an
   `Either`; `.orThrow` fails the stage on a `Left`.

   ```scala
   stage("Write failing test"):
     session.run("Write the failing test ...")   // commits on completion

   val pr = stage("Push + open PR"):   // later stage: the test commit exists now
     git.push().orThrow
     gh.createPr(title = "...", body = "...").orThrow
   ```

3. **Idempotent external effects, each in its own stage.** Put each PR-open,
   comment or push in its own stage. `gh.createPr` reuses an open PR for the
   branch. `gh.upsertComment(target, marker, body)` edits an earlier comment
   that carries `marker`. So a resumed stage updates instead of duplicating.
   `orcaCommentMarker(userPrompt, purpose)` gives a marker unique to the run.

## Long loops

A review loop is one stage, so one commit. When each iteration is long, write
the loop in the flow instead: one stage per iteration calling the coder
session's `.run`, so a resume picks up at the last finished iteration.

## Parallel work

The **flow thread** is the main thread of your script. A **fork** is a
function running in parallel under `Par.mapUnordered(n)(items)(f)`, which runs
`f` over `items` with at most `n` in parallel. Inside `f` you may call
`agent.run` and `chat.run`. You may not call `stage`, `agent.session` or
`session.run` there; they throw. Results come back in completion order, not
input order.
