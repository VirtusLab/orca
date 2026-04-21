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

lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "orca-core",
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
  .dependsOn(core, core % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-claude",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

lazy val codex = (project in file("codex"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "orca-codex",
    libraryDependencies ++= Seq(osLib, sttpClient, sttpClientOx)
  )

lazy val cli = (project in file("cli"))
  .dependsOn(core, claude, codex)
  .settings(commonSettings)
  .settings(
    name := "orca-cli",
    libraryDependencies ++= Seq(ox, mainargs, jline, fansi)
  )

lazy val orca = (project in file("."))
  .aggregate(core, claude, codex, cli)
  .settings(
    name := "orca",
    publish / skip := true
  )
