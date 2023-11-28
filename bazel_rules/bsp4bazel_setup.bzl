load("//private:load_tool.bzl", "load_tool")

# <--- Updated automatically by release job
_bsp4bazel_version = "0.0.29"
_build_artifact_shas = {
    "linux-x86": "8df5d251b327f051984d979030fdce7183fd8c7294ec3f4e3e00645971a42969",
    "macos-x86": "73ade12570a75ff1373486051c68fa85c296846b8840f64a17abdcfc32655807"
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