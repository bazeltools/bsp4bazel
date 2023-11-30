
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "com.example"

lazy val root = project
  .in(file("."))
  .settings(
    name := "simple-no-errors-bsp",
  )

