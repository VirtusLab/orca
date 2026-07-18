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
      // by the claude, codex and gemini backends, so chimp + the netty backend
      // live here — tools already hosts the cross-backend orca.backend
      // package — rather than in any single backend module.
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
    libraryDependencies ++= Seq(
      ox,
      jsoniterMacros,
      jsonSchemaValidator,
      scala3Compiler,
      munitScalacheck
    ),
    // The CC negative-compile suite invokes the Scala 3 compiler
    // (`dotty.tools.dotc.Main`) in-process against this
    // module's own test classpath (it needs orca.CheckedPar, orca.FlowControl,
    // the capability tokens, ox and the Scala library). flow's tests are not
    // forked, so `java.class.path` is only sbt's launcher classpath — unusable.
    // Materialise the classpath into a resource the suite reads. It must be
    // `dependencyClasspath`, NOT `fullClasspath`: the latter includes this
    // module's own Test products, whose task graph depends back on Test
    // resources — a cycle that deadlocks sbt's task engine (observed, not
    // hypothetical). dependencyClasspath still carries everything the fixtures
    // reference (tools/flow Compile classes plus external deps).
    Test / resourceGenerators += Def.task {
      val cp = (Test / dependencyClasspath).value
        .map(_.data.getAbsolutePath)
        .mkString(java.io.File.pathSeparator)
      val f = (Test / resourceManaged).value / "cc-test-classpath.txt"
      IO.write(f, cp)
      Seq(f)
    }.taskValue
  )

lazy val runner = (project in file("runner"))
  .dependsOn(
    tools,
    tools % "test->test",
    flow,
    claude,
    codex,
    opencode,
    pi,
    gemini
  )
  .settings(commonSettings)
  .settings(
    // Published as just "orca" so flow-script coordinates stay short.
    name := "orca",
    // Fork tests: `flow(...)` mutates the global logback root logger (OrcaLog's
    // per-run appender) and can `System.exit` on a NonFatal failure — a forked
    // JVM keeps that out of the shared test runner.
    Test / fork := true,
    // `runFlow`'s reentrancy guard (`FlowLock.acquireProcess`) is a
    // process-wide `AtomicBoolean` — correct for real usage (one `flow(...)`
    // per process), but sbt's default `Test / parallelExecution` would let two
    // unrelated test classes in this forked JVM both call
    // `flow(...)`/`runFlow(...)` concurrently (different workDirs, no real
    // conflict) and spuriously trip each other's guard. Serialize this
    // module's tests instead of keying the guard by workDir, which would
    // water down the exact single-process semantics the guard exists to
    // enforce.
    Test / parallelExecution := false,
    libraryDependencies ++= Seq(ox, mainargs, jline, fansi, jsoniterMacros)
  )

lazy val shell = (project in file("shell"))
  .dependsOn(runner, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-shell",
    Compile / mainClass := Some("orca.shell.Main"),
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros, ox, jline, jlineConsoleUi, fansi),
    // Bundles the top-level flows/*.sc scripts as jar resources under
    // orca/shell/flows/ (ADR 0021 §7), so `BuiltInFlows` can extract them to a
    // real path at runtime. Jar resources aren't listable, hence the
    // generated `index` sidecar (newline-separated filenames).
    Compile / resourceGenerators += Def.task {
      val srcDir = (ThisBuild / baseDirectory).value / "flows"
      val outDir = (Compile / resourceManaged).value / "orca" / "shell" / "flows"
      IO.createDirectory(outDir)
      val flowFiles = IO.listFiles(srcDir).filter(_.getName.endsWith(".sc")).sortBy(_.getName)
      val copied = flowFiles.map { f =>
        val target = outDir / f.getName
        IO.copyFile(f, target)
        target
      }
      val indexFile = outDir / "index"
      IO.write(indexFile, flowFiles.map(_.getName).mkString("\n"))
      copied.toSeq :+ indexFile
    }.taskValue
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
          file("examples"),
          file("flows")
        )
      )
      (sbtCoords ++ scalaCliCoords).distinct
    },
    // Subprojects inherit the stock `UpdateVersionInDocs`-backed `updateDocs`
    // from ossPublishSettings; disable aggregation so `release` doesn't also
    // invoke each of them (they'd noisily warn about missing `doc`/`docs`).
    updateDocs / aggregate := false
  )
  .aggregate(tools, flow, claude, codex, opencode, pi, gemini, runner, shell)
