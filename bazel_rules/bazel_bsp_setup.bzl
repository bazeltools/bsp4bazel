load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.24"
_build_artifact_shas = {
    "linux-x86": "b5c7340729dc63f8823396cfab7f818e9e12537d0d9ece6f79427af6921203f8",
    "macos-x86": "f5fcda41a59ffe712b2dd32d498c5b8e4992b379ffef82b9741ede1e1c26c4ad"
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