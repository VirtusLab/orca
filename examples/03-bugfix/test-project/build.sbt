ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "com.example"

lazy val root = (project in file("."))
  .settings(
    name := "calculator",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )
