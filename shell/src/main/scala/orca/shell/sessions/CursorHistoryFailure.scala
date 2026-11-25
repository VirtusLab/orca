package orca.shell.sessions

import orca.OrcaFlowException

private[shell] final class CursorHistoryFailure(message: String)
    extends OrcaFlowException(message)
