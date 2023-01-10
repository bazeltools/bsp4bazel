load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.21"
_build_artifact_shas = {
    "linux-x86": "c28430190654513f492fdf68a4b16bf70dcffb0aad4c450aa7d49fc1f668aa41",
    "macos-x86": "46f5314070dc360840d91c8c442f004c32d0f623cd0be78c87a6592575b5eab9"
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