load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.27"
_build_artifact_shas = {
    "linux-x86": "7fbd36c007b3d7915292c106ab66f29e4e370992ac7b6061c36a7ed98eaa0009",
    "macos-x86": "fc53fda3d02d7e383d2fea7708a6fcb7c9257db2de9bf58d2704a72113b147ad"
}
# --->

def _bazel_bsp_load(platform):
    name = "bazel-bsp-{}".format(platform)

    load_tool(
        name = name,
        urls = [
            "https://github.com/aishfenton/bazel-bsp/releases/download/{}/{}".format(_bazel_bsp_version, name),
        ],
        packaged = False,
        binary_path = "bazel-bsp-linux-x86",
        sha256 = _build_artifact_shas[platform],
    )

def bazel_bsp_setup():
    _bazel_bsp_load("linux-x86")
    _bazel_bsp_load("macos-x86")