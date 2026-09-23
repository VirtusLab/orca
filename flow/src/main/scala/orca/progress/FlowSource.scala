package orca.progress

import orca.agents.JsonData
import orca.util.TextUtil

/** The flow script a run executes, as the shell launched it. Recorded in the
  * progress header, so the shell's "Resume interrupted run" relaunches the same
  * script.
  */
enum FlowSource derives JsonData:
  /** Launched by catalog name (`implement.sc`): a resume looks the name up in
    * the catalog again, as re-running `orca run implement` would.
    */
  case Catalog(name: String)

  /** Launched by an absolute path to the script: a resume runs that file. Not
    * validated at decode, since an undecodable header is overwritten by a fresh
    * run; the shell validates the path before offering a resume.
    */
  case File(path: String)

  /** The catalog name or the full path, safe to print to a terminal. */
  def display: String = this match
    case Catalog(name) => TextUtil.oneline(name)
    case File(path)    => TextUtil.oneline(path)

  /** The script's filename, e.g. `implement.sc`. */
  def fileName: String = this match
    case Catalog(name) => name
    case File(path)    => path.substring(path.lastIndexOf('/') + 1)
