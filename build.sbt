import Dependencies.*

ThisBuild / scalaVersion      := V.scala
ThisBuild / organization      := "com.virtuslab"
ThisBuild / version           := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme     := Some("early-semver")

ThisBuild / scalacOptions ++= Seq(
  "-release", "21",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement"
)

ThisBuild / javacOptions ++= Seq("--release", "21")


lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(munit),
  testFrameworks += new TestFramework("munit.Framework")
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
      scribe,
      jsonSchemaValidator
    )
  )

lazy val claude = (project in file("claude"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-claude",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

lazy val codex = (project in file("codex"))
  .dependsOn(tools)
  .settings(commonSettings)
  .settings(
    name := "orca-codex",
    libraryDependencies ++= Seq(osLib, sttpClient, sttpClientOx)
  )

lazy val flow = (project in file("flow"))
  .dependsOn(tools)
  .settings(commonSettings)
  .settings(
    name := "orca-flow",
    libraryDependencies ++= Seq(ox, jsoniterMacros, jsonSchemaValidator)
  )

lazy val runner = (project in file("runner"))
  .dependsOn(tools, flow, claude, codex)
  .settings(commonSettings)
  .settings(
    // Published as just "orca" so flow-script coordinates stay short.
    name := "orca",
    libraryDependencies ++= Seq(ox, mainargs, jline, fansi, jsoniterMacros)
  )

lazy val orcaRoot = (project in file("."))
  .aggregate(tools, flow, claude, codex, runner)
  .settings(
    name := "orca-root",
    publish / skip := true
  )
