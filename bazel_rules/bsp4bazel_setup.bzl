load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bsp4bazel_version = "0.0.28"
_build_artifact_shas = {
    "linux-x86": "cbbf9263de1c1adbaa2d891ad8e1c4074123346495ff6a0ceddd732bd870edcd",
    "macos-x86": "45014e7ea5ea0a6ed3c114a957eef10bbfee8613fb1c8c740e6643ce87399c01"
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