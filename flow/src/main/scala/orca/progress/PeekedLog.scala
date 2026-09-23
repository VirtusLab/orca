package orca.progress

/** A run's own progress log as [[ProgressStore.peek]] found it before setup
  * touched the tree. It may hold uncommitted content, so it never says whether
  * to resume — only the read after the stash does — and carries no decoded
  * header for anything to route on.
  */
enum PeekedLog:
  case Absent

  /** Present, but the read failed — permissions, or a directory at the path.
    */
  case Unreadable(reason: String)

  /** Present, but it does not parse. Resumes nothing. */
  case Unparseable(bytes: IArray[Byte])

  /** Present and parses. */
  case Parseable(bytes: IArray[Byte])
