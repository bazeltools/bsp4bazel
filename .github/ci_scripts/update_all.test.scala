//> using file "project.test.scala"

class WorkflowTest extends munit.FunSuite:

  val files = FunFixture[os.Path](
    setup = { test =>
      val projectDir = os.pwd
      val tmpDir = os.temp.dir()

      println("Using project dir: " + projectDir)
      println("Using tmp dir: " + tmpDir)

      os.makeDir(tmpDir / "staging")
      os.makeDir(tmpDir / "repo")
      os.makeDir(tmpDir / "repo" / "bazel_rules")
      os.makeDir(tmpDir / "repo" / ".github")

      os.copy(
        projectDir / ".github" / "ci_scripts",
        tmpDir / "repo" / ".github" / "ci_scripts"
      )

      os.write.over(tmpDir / "repo" / "README.md", FixtureData.Readme)
      os.write.over(
        tmpDir / "repo" / "bazel_rules" / "bsp4bazel_setup.bzl",
        FixtureData.BazelRule
      )
      os.write.over(tmpDir / "repo" / "build.sbt", FixtureData.BuildFile)

      FixtureData.ArtifactShas.map { (name, sha) =>
        os.write.over(tmpDir / "staging" / name, sha)
      }

      tmpDir
    },
    teardown = { cwd =>
      // automatically deleted on JVM exit
    }
  )

  files.test("should update versions and shas") { cwd =>

    val workingDir = cwd / "repo"

    os.proc(workingDir / ".github" / "ci_scripts" / "update_all.sh", "0.0.20")
      .call(cwd = workingDir)

    assertEquals(os.exists(cwd / "staging" / "bazel_rules.tar.gz"), true)
    assertEquals(os.exists(cwd / "staging" / "bazel_rules.tar.gz.sha256"), true)

    val ruleSha = os.read(cwd / "staging" / "bazel_rules.tar.gz.sha256").trim
    val newVersion = "0.0.20"

    assertEquals(
      os.read(workingDir / "README.md"),
      s"""
# Bazel BSP
Current Version: [$newVersion](https://github.com/bazeltools/bsp4bazel/releases/tag/$newVersion)

```starlark
bsp4bazel_version = "$newVersion"
http_archive(
    name = "bsp4bazel-rules",
    sha256 = "$ruleSha",
    strip_prefix = "bazel_rules",
    type = "tar.gz",
    url = "https://github.com/bazeltools/bsp4bazel/releases/download/%s/bazel_rules.tar.gz" % bsp4bazel_version,
)

load("@bsp4bazel-rules//bazel_rules:bsp4bazel_setup.bzl", "bsp4bazel_setup")
bsp4bazel_setup()
```

More blah blah blah
""".trim
    )

    assertEquals(
      os.read(workingDir / "bazel_rules" / "bsp4bazel_setup.bzl"),
      s"""
load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bsp4bazel_version = "$newVersion"
_build_artifact_shas = {
    "linux-x86": "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f",
    "macos-x86": "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f"
}
# --->

def _bsp4bazel_load(platform):
    name = "bsp4bazel-{}".format(platform)
    ...

def bsp4bazel_setup():
    _bsp4bazel_load("linux-x86")
    _bsp4bazel_load("macos-x86")
""".trim
    )

    assertEquals(
      os.read(workingDir / "build.sbt"),
      s"""
val scala3Version = "3.3.0"

// <--- Updated automatically by release job
val bsp4BazelVersion = "$newVersion"
// --->

lazy val root = project
  .in(file("."))
  .settings(
    name := "bsp4bazel",
    organization := "bazeltools",
    version := bsp4BazelVersion, 
    scalaVersion := scala3Version,
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      "bspVersion" -> "2.0.0-M2"
    ),
    buildInfoPackage := "bazeltools.bsp4bazel"
  )
""".trim
    )

  }

object FixtureData:

  val ArtifactShas: Map[String, String] = Map(
    "bsp4bazel-linux-x86.sha256" -> "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f",
    "bsp4bazel-macos-x86.sha256" -> "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f"
  )

  val BazelRule = raw"""
load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bsp4bazel_version = "0.0.19"
_build_artifact_shas = {
    "linux-x86": "a3453ktnegakjdngf43yt334g34g3g34g34g",
    "amiga-m68000": "34534lsbflsldfb43mh34hlmbrebml34h34h",
}
# --->

def _bsp4bazel_load(platform):
    name = "bsp4bazel-{}".format(platform)
    ...

def bsp4bazel_setup():
    _bsp4bazel_load("linux-x86")
    _bsp4bazel_load("macos-x86")
""".trim

  val Readme = """
# Bazel BSP
Current Version: [0.0.19](https://github.com/bazeltools/bsp4bazel/releases/tag/0.0.19)

```starlark
bsp4bazel_version = "0.0.19"
http_archive(
    name = "bsp4bazel-rules",
    sha256 = "5da0e3c951a7ea50be908d3b97bf2f0b2da8713b99c6035e826dfce3302d5b39",
    strip_prefix = "bazel_rules",
    type = "tar.gz",
    url = "https://github.com/bazeltools/bsp4bazel/releases/download/%s/bazel_rules.tar.gz" % bsp4bazel_version,
)

load("@bsp4bazel-rules//bazel_rules:bsp4bazel_setup.bzl", "bsp4bazel_setup")
bsp4bazel_setup()
```

More blah blah blah
""".trim

  val BuildFile = """
val scala3Version = "3.3.0"

// <--- Updated automatically by release job
val bsp4BazelVersion = "0.0.19"
// --->

lazy val root = project
  .in(file("."))
  .settings(
    name := "bsp4bazel",
    organization := "bazeltools",
    version := bsp4BazelVersion, 
    scalaVersion := scala3Version,
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      "bspVersion" -> "2.0.0-M2"
    ),
    buildInfoPackage := "bazeltools.bsp4bazel"
  )
""".trim
