import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.{ossPublishSettings, updateDocs}
import com.softwaremill.UpdateVersionInDocs
import Dependencies.*

ThisBuild / scalaVersion := V.scala

ThisBuild / scalacOptions ++= Seq(
  "-release",
  "21",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement"
)

ThisBuild / javacOptions ++= Seq("--release", "21")

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "org.virtuslab",
  versionScheme := Some("semver-spec"),
  libraryDependencies ++= Seq(munit),
  testFrameworks += new TestFramework("munit.Framework"),
  homepage := Some(url("https://github.com/VirtusLab/orca")),
  organizationHomepage := Some(url("https://virtuslab.com")),
  licenses := Seq(
    "Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/VirtusLab/orca"),
      "scm:git:git@github.com:VirtusLab/orca.git"
    )
  ),
  developers := List(
    Developer(
      id = "virtuslab",
      name = "VirtusLab",
      email = "info@virtuslab.com",
      url = url("https://virtuslab.com")
    )
  )
)

lazy val tools = (project in file("tools"))
  .settings(commonSettings)
  .settings(
    name := "orca-tools",
    libraryDependencies ++= Seq(
      osLib,
      jsoniter,
      jsoniterMacros,
      tapirApispec,
      sttpApispecCirce,
      ox,
      jsonSchemaValidator,
      // The shared MCP server (orca.backend.mcp.AskUserMcpServer) is consumed
      // by both claude and codex backends, so chimp + the netty backend live
      // here rather than in claude.
      chimp,
      tapirNettySync
    )
  )

lazy val claude = (project in file("claude"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-claude",
    libraryDependencies ++= Seq(
      osLib,
      jsoniter,
      jsoniterMacros,
      logback
    )
  )

lazy val codex = (project in file("codex"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-codex",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

lazy val pi = (project in file("pi"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-pi",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

// The OpenCode backend talks HTTP+SSE to a headless `opencode serve` (ADR
// 0014); it uses the JDK's java.net.http client (no extra dependency) behind a
// small testable trait, plus jsoniter for the wire DTOs.
lazy val opencode = (project in file("opencode"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-opencode",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

lazy val gemini = (project in file("gemini"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-gemini",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

lazy val flow = (project in file("flow"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-flow",
    libraryDependencies ++= Seq(ox, jsoniterMacros, jsonSchemaValidator)
  )

lazy val runner = (project in file("runner"))
  .dependsOn(tools, tools % "test->test", flow, claude, codex, opencode, pi, gemini)
  .settings(commonSettings)
  .settings(
    // Published as just "orca" so flow-script coordinates stay short.
    name := "orca",
    // Fork tests: `flow(...)` mutates the global logback root logger (OrcaLog's
    // per-run appender) and can `System.exit` on a NonFatal failure — a forked
    // JVM keeps that out of the shared test runner.
    Test / fork := true,
    libraryDependencies ++= Seq(ox, mainargs, jline, fansi, jsoniterMacros)
  )

lazy val orcaRoot = (project in file("."))
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    name := "orca-root",
    // Chain two passes so both coord forms get bumped: sbt-style
    // `"org" %% "name" % "ver"` (the stock `UpdateVersionInDocs`) and scala-cli
    // `using dep "org::name:ver"` (the form Orca's flow scripts and READMEs
    // actually use today). The scala-cli pass also keeps `//> using scala`
    // pins in sync with `V.scala` — the build's own Scala version and the
    // floor consumers must be on.
    updateDocs := {
      val log = sLog.value
      val org = organization.value
      val ver = version.value
      val sbtCoords =
        UpdateVersionInDocs(log, org, ver, List(file("README.md")))
      val scalaCliCoords = UpdateScalaCliVersionInDocs(
        log,
        org,
        ver,
        V.scala,
        List(
          file("README.md"),
          file("AGENTS.md"),
          file("examples")
        )
      )
      (sbtCoords ++ scalaCliCoords).distinct
    },
    // Subprojects inherit the stock `UpdateVersionInDocs`-backed `updateDocs`
    // from ossPublishSettings; disable aggregation so `release` doesn't also
    // invoke each of them (they'd noisily warn about missing `doc`/`docs`).
    updateDocs / aggregate := false
  )
  .aggregate(tools, flow, claude, codex, opencode, pi, gemini, runner)
