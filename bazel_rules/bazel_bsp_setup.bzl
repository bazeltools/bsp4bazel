load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.26"
_build_artifact_shas = {
    "linux-x86": "646c9b27d155b88a0b6ce94a80395ddd278ea27a9cca797f432adb5dc01cacee",
    "macos-x86": "e166a48b9ffd70d1b25232d40a4f0bd2772a3ac3e757d3acbe91928864e7fc7e"
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