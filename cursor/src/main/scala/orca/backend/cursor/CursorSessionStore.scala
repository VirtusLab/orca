package orca.backend.cursor

import orca.subprocess.CliRunner
import ox.{discard, fork, supervised, timeoutOption, use}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Read-only lookup of Cursor's checkout-scoped persisted history. */
private[orca] object CursorSessionStore:
  def defaultChatsRoot: os.Path = os.home / ".cursor" / "chats"

  def validId(id: String): Boolean =
    id.matches(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

  /** Resolving symlinks also maps macOS /var to /private/var. */
  def database(
      workDir: os.Path,
      id: String,
      chatsRoot: os.Path
  ): Option[os.Path] =
    if !validId(id) then None
    else
      try
        val canonical = workDir.toNIO.toRealPath()
        Option.when(Files.isDirectory(canonical)):
          val hash = HexFormat
            .of()
            .formatHex(
              MessageDigest
                .getInstance("MD5")
                .digest(canonical.toString.getBytes(UTF_8))
            )
          chatsRoot / hash / id / "store.db"
      catch case NonFatal(_) => None

  def resumable(
      workDir: os.Path,
      id: String,
      chatsRoot: os.Path,
      cli: CliRunner,
      timeout: FiniteDuration = 10.seconds
  ): Boolean =
    database(workDir, id, chatsRoot).exists: path =>
      try
        if !Files.isRegularFile(path.toNIO) || !Files.isReadable(path.toNIO)
        then false
        else
          // Immutable mode ignores WAL-only history and never creates a store.
          // EXISTS bounds the result without reading conversation contents.
          timeoutOption(timeout):
            supervised:
              use(
                cli.spawnPiped(
                  Seq(
                    "sqlite3",
                    "-batch",
                    "-noheader",
                    "-readonly",
                    "-init",
                    "/dev/null",
                    path.toNIO.toUri.toASCIIString + "?mode=ro&immutable=1",
                    "SELECT EXISTS (SELECT 1 FROM meta WHERE key = '0') AND EXISTS (SELECT 1 FROM blobs);"
                  ),
                  cwd = workDir
                ),
                process =>
                  process.destroyForciblyTree()
                  process.waitForExit().discard
              ): process =>
                process.closeStdin()
                val stdout = fork:
                  var seen = false
                  var valid = true
                  for line <- process.stdoutLines do
                    valid = valid && !seen && line == "1"
                    seen = true
                  seen && valid
                val stderr = fork:
                  process.stderrLines.foreach(_ => ())
                val exitCode = process.waitForExit()
                val scalar = stdout.join()
                stderr.join()
                exitCode == 0 && scalar
          .contains(true)
      catch case NonFatal(_) => false

  def missingHistory(workDir: os.Path): String =
    s"no persisted Cursor history could be verified for $workDir; " +
      "install sqlite3 on PATH if unavailable, then retry; if history is missing " +
      "or unverified, rerun Orca in that checkout to re-seed the conversation"
