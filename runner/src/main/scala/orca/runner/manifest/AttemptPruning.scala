package orca.runner.manifest

import orca.{AttemptId, OrcaDir}
import orca.util.JsonFile

import scala.util.control.NonFatal

/** Bounds `.orca/cache/attempts/` by attempt count. Runs once per attempt, at
  * the writer's construction, after its own manifest is on disk.
  */
private[manifest] object AttemptPruning:

  /** The size of each of [[keptIds]]' two kept sets — an attempt owns several
    * files (`OrcaDir.attemptIdOf`), and the sets overlap, so the attempts
    * directory holds between this many and twice this many attempts.
    */
  val MaxKeptAttempts: Int = 20

  /** One attempt's presence in the directory: the files it left, and whether
    * its manifest is [[AttemptManifest.continuable]]. A manifest that does not
    * load is not continuable: the shell cannot offer its sessions either.
    */
  case class Attempt(id: AttemptId, files: List[os.Path], continuable: Boolean)

  /** Deletes every file of every attempt outside [[keptIds]]. Grouping by
    * attempt id rather than counting files is what keeps the budget in
    * attempts, and what stops a cost or trace log outliving the manifest it
    * belongs to.
    *
    * Each delete is guarded on its own, so one file a concurrent cleanup got to
    * first does not stop the rest; the caller guards the listing.
    */
  def prune(dir: os.Path): Unit =
    val attempts = attemptsNewestFirst(dir)
    val kept = keptIds(attempts, MaxKeptAttempts)
    for
      attempt <- attempts if !kept.contains(attempt.id)
      file <- attempt.files
    do
      try os.remove(file): Unit
      catch case NonFatal(_) => ()

  /** Two kept sets: the newest `max` continuable attempts, and the newest `max`
    * attempts of any kind.
    *
    * The first is what keeps the shell's "continue a session" list full.
    * Session-less attempts are common — every fresh run spends tokens naming
    * its branch (`BranchNamingStrategy.shortenPrompt`) before its first stage,
    * so one cancelled at the plan prompt leaves a manifest with nothing to
    * continue — and on the newest-first ranking alone `max` of them would evict
    * every continuable attempt.
    *
    * The second bounds a workdir that stops committing sessions altogether,
    * where the first set alone has nothing to rank against.
    */
  def keptIds(newestFirst: List[Attempt], max: Int): Set[AttemptId] =
    def newest(attempts: List[Attempt]): Set[AttemptId] =
      attempts.take(max).map(_.id).toSet
    newest(newestFirst.filter(_.continuable)) ++ newest(newestFirst)

  /** The directory's attempts, newest first; each manifest is decoded once
    * here. An attempt with only some of its files is still one attempt.
    */
  private def attemptsNewestFirst(dir: os.Path): List[Attempt] =
    os.list(dir)
      .groupBy(OrcaDir.attemptIdOf)
      .toList
      .collect:
        case (Some(id), files) =>
          Attempt(id, files.toList, files.exists(continuableManifest))
      .sortBy(_.id.value)
      .reverse

  private def continuableManifest(file: os.Path): Boolean =
    if !OrcaDir.isManifest(file) then false
    else
      JsonFile.read[AttemptManifest](file) match
        case JsonFile.Read.Loaded(manifest) => manifest.continuable
        case _                              => false
