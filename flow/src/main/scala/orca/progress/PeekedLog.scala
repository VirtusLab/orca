package orca.progress

/** A run's own progress log as [[ProgressStore.peek]] found it before setup
  * touched the tree. It may hold uncommitted content, so it carries no decoded
  * header: only the read after the stash decides fresh vs resume.
  */
private[orca] enum PeekedLog:
  case Absent

  /** Present, but it does not parse. */
  case Unparseable(bytes: IArray[Byte])

  /** Present and parses. */
  case Parseable(bytes: IArray[Byte])
