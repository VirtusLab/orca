package orca

trait FsTool:
  def read(path: String): String
  def write(path: String, content: String): Unit
  def list(glob: String): List[String]
