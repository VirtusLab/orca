package orca.shell.menu

import org.jline.terminal.Terminal

/** Opens `path` in the user's editor and returns its exit code —
  * `EditAction.editInPlace` in production.
  */
private[menu] type SpawnEditor = (Terminal, os.Path) => Int
