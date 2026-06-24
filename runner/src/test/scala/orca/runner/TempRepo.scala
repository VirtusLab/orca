package orca.runner

import orca.testkit.GitRepo

/** Test helper: a fresh temp git repo with one seed commit, so `flow(...)` —
  * which now stashes, branches, and commits a progress log as part of its
  * lifecycle (ADR 0018 §2.5) — runs in isolation instead of mutating the
  * developer's checkout.
  */
object TempRepo:
  def create(): os.Path = GitRepo.seeded()
