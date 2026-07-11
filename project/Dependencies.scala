import sbt.*

object Dependencies {
  object V {
    val scala = "3.8.4"
    val osLib = "0.11.4"
    val jsoniter = "2.38.17"
    val tapir = "1.13.25"
    val sttpApispec = "0.11.10"
    val ox = "1.0.5"
    val mainargs = "0.7.6"
    val jline = "3.28.0"
    val fansi = "0.5.0"
    val munit = "1.1.1"
    val jsonSchemaValidator = "1.5.4"
    val chimp = "0.1.8"
    val logback = "1.5.18"
  }

  val osLib = "com.lihaoyi" %% "os-lib" % V.osLib
  val jsoniter =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % V.jsoniter
  val jsoniterMacros =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter

  val tapirApispec =
    "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % V.tapir
  val sttpApispecCirce =
    "com.softwaremill.sttp.apispec" %% "jsonschema-circe" % V.sttpApispec

  val ox = "com.softwaremill.ox" %% "core" % V.ox

  val mainargs = "com.lihaoyi" %% "mainargs" % V.mainargs
  val jline = "org.jline" % "jline" % V.jline
  val fansi = "com.lihaoyi" %% "fansi" % V.fansi

  val munit = "org.scalameta" %% "munit" % V.munit % Test

  // The Scala 3 compiler as a library, so the CC-enforcement negative-compile
  // suite (orca.CcNegativeCompileTest) can invoke the compiler's entry point
  // (`dotty.tools.dotc.Main` — the class the `scalac`/`scala` launchers
  // themselves run) in-process on fixture sources. Pinned to V.scala so the
  // checked-compilation phases match the build's own compiler. Test-only.
  val scala3Compiler = "org.scala-lang" %% "scala3-compiler" % V.scala % Test
  val jsonSchemaValidator =
    "com.networknt" % "json-schema-validator" % V.jsonSchemaValidator % Test

  // chimp bundles an MCP server endpoint as a Tapir ServerEndpoint; pair it
  // with the Netty-sync interpreter to mount it on an Ox-friendly stack.
  val chimp = "com.softwaremill.chimp" %% "core" % V.chimp
  val tapirNettySync =
    "com.softwaremill.sttp.tapir" %% "tapir-netty-server-sync" % V.tapir

  // SLF4J binding so chimp/tapir/netty stop printing the "No SLF4J providers
  // found" warning; a logback.xml on the classpath pins the noisy loggers to
  // ERROR so the orca event log isn't drowned in framework chatter.
  val logback = "ch.qos.logback" % "logback-classic" % V.logback
}
