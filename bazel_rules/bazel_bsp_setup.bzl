load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.23"
_build_artifact_shas = {
    "linux-x86": "a49adf476305edfecbfe2111d6c036e17a6d32d91d0c076dd0fbe7b7a95776fa",
    "macos-x86": "f463688902d1b2dcc5effcb0ebb76dd68790a59e9b9de2eb53e939f1e65da8a5"
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