//> using file "project.scala"

class UpdateArtifactShasTest extends munit.FunSuite {

  test("should generate sha definitions") {
    val pythonStr = generatePythonMap(
      FixtureData.artifactShas.filterKeys(_.contains("bazel-bsp")).toMap
    )
    assertEquals(pythonStr, FixtureData.pythonMap)
  }

  test("should substitute python sha map") {
    val output = substituteBazelRule(
      FixtureData.originalRule,
      FixtureData.pythonMap
    )

    assertEquals(output, FixtureData.updatedRule)
  }

  test("should substitute sha in readme") {
    val result = substituteReadme(FixtureData.originalReadme, FixtureData.artifactShas("bazel-rules.tar.gz.sha256"))
    assertEquals(
      result,
      FixtureData.updatedReadme
    )
  }

}

object FixtureData:

  val artifactShas: Map[String, String] = Map(
    "bazel-bsp-linux-x86.sha256" -> "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f",
    "bazel-bsp-macos-x86.sha256" -> "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f",
    "bazel-rules.tar.gz.sha256" ->  "11c6fd27bc6cb5d4194dffe772b285a22dc5355a7c64ed53bea5c735ac9ddf04"
  )

  val pythonMap = """
_build_artifact_shas = {
    "linux-x86": "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f",
    "macos-x86": "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f"
}
""".trim

  val originalRule = raw"""
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

    load_tool(
        name = name,
        urls = [
            "https://github.com/aishfenton/bazel-bsp/releases/download/v{}/{}".format(_bazel_bsp_version, name),
        ],
        packaged = False,
        binary_path = "bazel-bsp-linux-x86",
        sha256 = _build_artifact_shas[platform],
    )

def bazel_bsp_setup():
    _bazel_bsp_load("linux-x86")
    _bazel_bsp_load("macos-x86")
""".trim

  val updatedRule = raw"""
load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.19"
$pythonMap
# --->

def _bazel_bsp_load(platform):
    name = "bazel-bsp-{}".format(platform)

    load_tool(
        name = name,
        urls = [
            "https://github.com/aishfenton/bazel-bsp/releases/download/v{}/{}".format(_bazel_bsp_version, name),
        ],
        packaged = False,
        binary_path = "bazel-bsp-linux-x86",
        sha256 = _build_artifact_shas[platform],
    )

def bazel_bsp_setup():
    _bazel_bsp_load("linux-x86")
    _bazel_bsp_load("macos-x86")
""".trim

  val originalReadme = """
# TTEST README 
blah
blah

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

  val updatedReadme = """
# TTEST README 
blah
blah

```starlark
bazel_bsp_version = "0.0.19"
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