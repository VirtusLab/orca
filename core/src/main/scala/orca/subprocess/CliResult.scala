package orca.subprocess

/** A finished child process: its exit code and captured output. */
case class CliResult(exitCode: Int, stdout: String, stderr: String)
