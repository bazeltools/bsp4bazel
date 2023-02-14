load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bsp4bazel_version = "0.0.27"
_build_artifact_shas = {
    "linux-x86": "7fbd36c007b3d7915292c106ab66f29e4e370992ac7b6061c36a7ed98eaa0009",
    "macos-x86": "fc53fda3d02d7e383d2fea7708a6fcb7c9257db2de9bf58d2704a72113b147ad"
}
# --->

def _bsp4bazel_load(platform):
    name = "bsp4bazel-{}".format(platform)

    load_tool(
        name = name,
        urls = [
            "https://github.com/bazeltools/bsp4bazel/releases/download/{}/{}".format(_bsp4bazel_version, name),
        ],
        packaged = False,
        binary_path = "bsp4bazel-linux-x86",
        sha256 = _build_artifact_shas[platform],
    )

def bsp4bazel_setup():
    _bsp4bazel_load("linux-x86")
    _bsp4bazel_load("macos-x86")