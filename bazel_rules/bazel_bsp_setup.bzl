load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bazel_bsp_version = "0.0.22"
_build_artifact_shas = {
    "linux-x86": "786ef582822c357637ed51e3fcffea088ecb7df4bd6114854a4ac21ac1870a87",
    "macos-x86": "213949a22e13e09b107b20ceaec6c229e9ff2f4bf6f1d39a6596bad2720f1382"
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