//> using file "project.scala"

import scala.collection.mutable.Stack

class WorkflowTest extends munit.FunSuite:

  val files = FunFixture[(os.Path, Console)](
    setup = { test =>
      val tmpDir = os.temp.dir()
      val console = Console.default 

      os.makeDir(tmpDir / "downloads")
      os.makeDir(tmpDir / "repo")
      os.makeDir(tmpDir / "repo" / "bazel_rules")

      os.write.over(tmpDir / "repo" / "README.md", FixtureData.Readme)
      os.write.over(tmpDir / "repo" / "bazel_rules" / "bazel_bsp_setup.bzl", FixtureData.BazelRule)
      os.write.over(tmpDir / "repo" / "build.sbt", FixtureData.BuildFile)

      FixtureData.ArtifactShas.map { (name, sha) => 
        os.write.over(tmpDir / "downloads" / name, sha) 
      }

      (tmpDir, console)
    },
    teardown = { (cwd, console) =>
      // automatically deleted on JVM exit
    }
  )

  files.test("should update versions and shas") { (cwd, console) =>

    val workingDir = cwd / "repo"

    currentVersion("build.sbt", workingDir, console)
    val currentVer = console.outLines.head.trim
    
    updateVersions(currentVer, "0.0.20", workingDir, console)
    updateArtifactShas(
      "bazel_rules/bazel_bsp_setup.bzl",
      "README.md",
      "../downloads",
      workingDir,
      console
    )

    assertEquals(
      os.read(workingDir / "README.md"),
      """
# Bazel BSP
Current Version: [0.0.20](https://github.com/aishfenton/bazel-bsp/releases/tag/v0.0.20)

```starlark
bazel_bsp_version = "0.0.20"
http_archive(
    name = "bazel-bsp-rules",
    sha256 = "11c6fd27bc6cb5d4194dffe772b285a22dc5355a7c64ed53bea5c735ac9ddf04",
    strip_prefix = "bazel_rules",
    type = "tar.gz",
    url = "https://github.com/aishfenton/bazel-bsp/releases/download/v{}/bazel_rules.tar.gz" % bazel_bsp_version,
)

load("@bazel-bsp-rules//bazel_rules:bazel_bsp_setup.bzl", "bazel_bsp_setup")
bazel_bsp_setup()
```

More blah blah blah
""".trim
    )

    assertEquals(
      os.read(workingDir / "bazel_rules" / "bazel_bsp_setup.bzl"),
      """
load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.20"
_build_artifact_shas = {
    "linux-x86": "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f",
    "macos-x86": "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f"
}
# --->

def _bazel_bsp_load(platform):
    name = "bazel-bsp-{}".format(platform)
    ...

def bazel_bsp_setup():
    _bazel_bsp_load("linux-x86")
    _bazel_bsp_load("macos-x86")
""".trim
    )

    assertEquals(
      os.read(workingDir / "build.sbt"),
      """
val scala3Version = "3.2.1"

// <--- Updated automatically by release job
val bazelBspVersion = "0.0.20"
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
    )

  }
