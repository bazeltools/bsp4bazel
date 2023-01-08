_build_file_template = """
load("@bazel-bsp-rules//private:wrap_executable.bzl", "wrap_executable")

wrap_executable(
    name = "{name}",
    executable_path = "{executable_path}",
    visibility = ["//visibility:public"],
)
"""

def _load_tool_impl(ctx):
    """Implementation of the load_tool rule."""

    if not ctx.attr.sha256:
        fail("Must specify a sha256")

    all_urls = []
    if ctx.attr.urls:
        all_urls = ctx.attr.urls

    ctx.file("WORKSPACE", "workspace(name = \"{name}\")\n".format(name = ctx.name))
    ctx.file("BUILD.bazel", _build_file_template.format(name = "bin", executable_path = ctx.attr.binary_path))

    download_info = None
    if ctx.attr.packaged:
        download_info = ctx.download_and_extract(
            url = all_urls,
            sha256 = ctx.attr.sha256,
            stripPrefix = ctx.attr.strip_prefix,
        )
    else:
        download_info = ctx.download(
            url = all_urls,
            output = ctx.attr.binary_path,
            sha256 = ctx.attr.sha256,
            executable = True,
        )

_load_tool_attrs = {
    "urls": attr.string_list(
        doc =
            """A list of URLs to a file that will be made available to Bazel.
Each entry must be a file, http or https URL. Redirections are followed.
URLs are tried in order until one succeeds, so you should list local mirrors first.
If all downloads fail, the rule will fail.""",
    ),
    "sha256": attr.string(
        doc = """The expected SHA-256 of the file downloaded.""",
    ),
    "packaged": attr.bool(default = True),
    "strip_prefix": attr.string(),
    "binary_path": attr.string(),
}

load_tool = repository_rule(
    implementation = _load_tool_impl,
    attrs = _load_tool_attrs,
)
