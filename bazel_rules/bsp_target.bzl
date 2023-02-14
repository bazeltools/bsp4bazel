load("//private:sources_aspect.bzl", "SourceFiles", "collect_source_files_aspect")
load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")

def _owner_to_bazel_file(fileLabel):
    workspace = fileLabel.workspace_root
    package = fileLabel.package
    if len(workspace) > 0:
        workspace = workspace + "/"
    return workspace + package 

def _bsp_target_impl(ctx):
    paths = sorted([f.path for f in ctx.attr.target[SourceFiles].transitive_source_files.to_list()])
    packages = sorted(depset([_owner_to_bazel_file(f.owner) for f in ctx.attr.target[SourceFiles].transitive_source_files.to_list()]).to_list())

    toolchain = ctx.toolchains["@io_bazel_rules_scala//scala:toolchain_type"]
    compile_jars = [jo.path for jo in ctx.attr._scala_toolchain[JavaInfo].compile_jars.to_list()]

    print([f.path for f in ctx.files.target])

    ctx.actions.write(ctx.outputs.bsp_target, """
{{ 
    "scala_version": "{}", 
    "scalac_options": {},
    "scala_compile_jars": {},
    "sources": {},
    "packages": {}
}}
""".format(SCALA_VERSION, toolchain.scalacopts, compile_jars, paths, packages))

    return DefaultInfo(
        runfiles = ctx.runfiles(files = [ctx.outputs.bsp_target]),
    )

bsp_target = rule(
    implementation = _bsp_target_impl,
    attrs = {
        "target": attr.label(aspects = [collect_source_files_aspect]),
        "_scala_toolchain": attr.label(
            default = Label("@io_bazel_rules_scala//scala/private/toolchain_deps:scala_library_classpath"),
            allow_files = False,
        ),
    },
    toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
    outputs = {
        "bsp_target": "bsp_target.json",
    },
)
