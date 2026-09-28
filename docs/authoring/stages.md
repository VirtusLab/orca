# Stages

`stage(name)(body)` is the committing, resumable unit of work. When the body
completes successfully, Orca records its result in the progress log and makes
a single commit, containing the code changes together with the log update. On
a re-run, a stage that already has a recorded result is skipped, and the stored
result is returned instead of running the body again.

```scala
def stage[T: JsonData](name: String, commitMessage: Option[T => String] = None)(body: => T): T
```

The signature is simplified: the body also receives the capability tokens
described in [Capabilities](capabilities.md).

A few things to note:

- The result type `T` needs a `JsonData` instance, so that it can be stored in
  the progress log. `case class Foo(...) derives JsonData` is enough; `Unit`,
  `String` and the library's own types already have one.
- The commit message defaults to a summary of the diff, written by
  `codingAgent.cheap`. Pass `commitMessage` to override it.
- Stages can nest; the output indents nested stages.
- The stage name appears in the event log, in the commit message and in the
  resume preamble, so choose one that a reader understands without looking at
  the code, for example `"Push + open PR"`.

## Side effects happen inside stages

Every side-effecting call must be made inside a `stage` body, and the compiler
enforces this: a mutation outside a stage does not compile. This covers
`git.push`, `fs.write`, `gh` writes, and every `agent.*.run`.

The following can run anywhere, inside or outside a stage:

- pure reads, such as `git.uncommittedDiff`, `git.changedFiles`,
  `gh.readIssue`, `gh.availability` and `fs.read`
- `display(message)`, which only prints progress output; there is no stage, no
  commit and no log entry
- `fail(message)`, which aborts the run with a message; the run stays on the
  feature branch, so a re-run resumes
- `agent.session(name, seed)`, since creating the handle only registers a
  name; running the session is the side effect

## Authoring rules

The compiler does not check the following rules, but they keep a flow
resumable.

1. **Do not stage reads.** A stage that contains only reads wastes a commit and
   a checkpoint.

2. **Push in a later stage than the edit.** A stage commits only when it
   completes, so a `git.push()` in the same stage as the edit pushes nothing.
   Put the push in a separate, later stage. Note that `git.push()` and
   `gh.createPr` return an `Either`; `.orThrow` fails the stage on a `Left`.

   ```scala
   stage("Write failing test"):
     session.run("Write the failing test ...")   // commits on completion

   val pr = stage("Push + open PR"):   // later stage: the test commit exists now
     git.push().orThrow
     gh.createPr(title = "...", body = "...").orThrow
   ```

3. **Idempotent external effects, each in its own stage.** Put each PR-open,
   comment or push in its own stage. `gh.createPr` reuses an open PR for the
   branch, and `gh.upsertComment(target, marker, body)` edits an earlier
   comment that carries `marker`. In other words, a resumed stage updates
   instead of duplicating. `orcaCommentMarker(userPrompt, purpose)` gives you a
   marker that is unique to the run.

## Long loops

A review loop is one stage, so it produces one commit. When each iteration is
long, it is better to write the loop in the flow itself, with one stage per
iteration calling the coder session's `.run`, so that a resume picks up at the
last finished iteration.

## Parallel work

The **flow thread** is the main thread of your script. A **fork** is a
function running in parallel under `Par.mapUnordered(n)(items)(f)`, which runs
`f` over `items` with at most `n` in parallel. Inside `f` you may call
`agent.run` and `chat.run`. You may not call `stage`, `agent.session` or
`session.run` there; these throw. Note that results come back in completion
order, not in input order.
