//> using lib "org.scalameta::munit::0.7.29"

import math.Ordering.Implicits.infixOrderingOps

class ProjectTest extends munit.FunSuite {

  test("should parse SemVer versions") {
    assertEquals(
      SemVer.fromString("1.2.20"),
      Some(SemVer(1, 2, 20))
    )
  }

  test("should order SemVer versions") {
    assert(
      SemVer(1, 10, 20) < SemVer(2, 0, 1),
      "1.10.20 should be less than 2.0.1"
    )
    assert(
      SemVer(1, 2, 9) < SemVer(1, 3, 0),
      "1.2.9 should be less than 1.3.0"
    )
    assert(
      SemVer(1, 2, 20) < SemVer(1, 2, 21),
      "1.2.20 should be less than 1.2.21"
    )
  }

  test("should work like sed") {

    val lines = """
A quick brown fox
jumped over the 
lazy dog
""".trim

    val result1 = sed(lines, "over".r, "ed".r, "ing")
    assertEquals(
      result1,
      Some("""
A quick brown fox
jumping over the 
lazy dog
    """.trim
      )
    )

    val result2 = sed(lines, "not-there".r, "ed".r, "ing")
    assertEquals(result2, None)
  }

}

object FixtureData:

  val ArtifactShas: Map[String, String] = Map(
    "bazel-bsp-linux-x86.sha256" -> "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f",
    "bazel-bsp-macos-x86.sha256" -> "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f",
    "bazel_rules.tar.gz.sha256" ->  "11c6fd27bc6cb5d4194dffe772b285a22dc5355a7c64ed53bea5c735ac9ddf04"
  )

  val BazelRule = raw"""
load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.19"
_build_artifact_shas = {
    "linux-x86": "a3453ktnegakjdngf43yt334g34g3g34g34g",
    "amiga-m68000": "34534lsbflsldfb43mh34hlmbrebml34h34h",
}
# --->

def _bazel_bsp_load(platform):
    name = "bazel-bsp-{}".format(platform)
    ...

def bazel_bsp_setup():
    _bazel_bsp_load("linux-x86")
    _bazel_bsp_load("macos-x86")
""".trim

  val Readme = """
# Bazel BSP
Current Version: [0.0.19](https://github.com/aishfenton/bazel-bsp/releases/tag/v0.0.19)

```starlark
bazel_bsp_version = "0.0.19"
http_archive(
    name = "bazel-bsp-rules",
    sha256 = "5da0e3c951a7ea50be908d3b97bf2f0b2da8713b99c6035e826dfce3302d5b39",
    strip_prefix = "bazel_rules",
    type = "tar.gz",
    url = "https://github.com/aishfenton/bazel-bsp/releases/download/v{}/bazel_rules.tar.gz" % bazel_bsp_version,
)

load("@bazel-bsp-rules//bazel_rules:bazel_bsp_setup.bzl", "bazel_bsp_setup")
bazel_bsp_setup()
```

More blah blah blah
""".trim

  val BuildFile = """
val scala3Version = "3.2.1"

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
