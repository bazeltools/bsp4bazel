//> using scala "3.2"
//> using lib "com.lihaoyi::os-lib:0.9.0"
//> using lib "org.scalameta::munit::0.7.29"

class CurrentVersionTest extends munit.FunSuite {

  test("should extract version from build file") {
    val version = extractVersion(CurrentVersionTest.buildContent)
    assertEquals(version.map(_.asString), Some("0.0.19"))
  }

}

object CurrentVersionTest:

  val buildContent = raw"""
val scala3Version = "3.2.1"

enablePlugins(GraalVMNativeImagePlugin)
enablePlugins(BuildInfoPlugin)

// <--- Updated automatically by release job
val bazelBspVersion = "0.0.19"
// --->

lazy val root = project
  .in(file("."))
  .settings(
    name := "bazel-bsp",
    organization := "afenton",
    version := bazelBspVersion, 
    scalaVersion := scala3Version,
    maintainer := "Aish Fenton",
    scalacOptions ++= Seq(
      "-explain"
    ),
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      "bspVersion" -> "2.0.0-M2"
    ),
    buildInfoPackage := "afenton.bazel.bsp"
  )
""".trim

