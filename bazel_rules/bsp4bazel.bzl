load("//private:load_tool.bzl", "load_tool")
load("//private:bsp_workspace_info.bzl", _bsp_workspace_info = "bsp_workspace_info")

# <--- Updated automatically by release job
_bsp4bazel_version = "0.0.30"
_build_artifact_shas = {
    "linux-x86": "77c0d32ed13dfa5212b1ad926528b1afff3de2bc6a2d57e78b7075df5341b486",
    "macos-x86": "79b8a735e6a2b74fe0ce18f4318797728efec5866081cae91512aa605ae8063e"
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

def bsp_workspace_info(name = "bsp_workspace_info"):
    if (name != "bsp_workspace_info"):
        fail("name must be 'bsp_workspace_info'")

    _bsp_workspace_info(name = name)