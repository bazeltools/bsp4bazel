load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.25"
_build_artifact_shas = {
    "linux-x86": "f79bf65d84d98b55190f1420d2487fd3dcef44d0120dfdb6b0171113d197d382",
    "macos-x86": "d794e4fd7aeaf88ac9ce30c3390c20fa14c3f76783f57204ddd994ac20ae3932"
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