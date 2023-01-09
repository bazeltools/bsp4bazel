//> using scala "3.2"
//> using lib "com.lihaoyi::os-lib:0.9.0"
//> using lib "org.scalameta::munit::0.7.29"

class UpdateArtifactShasTest extends munit.FunSuite {

  test("should generate sha definitions") {
    val pythonStr = generateShaDefinitions(
      UpdateArtifactShasTest.artifactShas
    )
    assertEquals(pythonStr, UpdateArtifactShasTest.newDefinitions)
  }

  test("should substitute sha definitions") {
    val output = substituteShaDefinitions(
      UpdateArtifactShasTest.originalContent,
      UpdateArtifactShasTest.newDefinitions
    )

    assertEquals(output, UpdateArtifactShasTest.expectedContent)
  }

}

object UpdateArtifactShasTest:
  val artifactShas: List[(os.Path, String)] = List(
    (
      os.Path("/downloads/bazel-bsp-linux-x86.sha256"),
      "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f"
    ),
    (
      os.Path("/downloads/bazel-bsp-macos-x86.sha256"),
      "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f"
    )
  )

  val newDefinitions = """
_build_artifact_shas = {
    "linux-x86": "83da2ffc0ab594a348f7828888d9e4c761ec128c38b1f013434f51f258cd6b9f",
    "macos-x86": "2d450d6cb18c8e0f436389ff0fd439694336276f53966e6d01206de4bc1f376f"
}
""".trim

  val originalContent = raw"""
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

  val expectedContent = raw"""
load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.19"
$newDefinitions
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
