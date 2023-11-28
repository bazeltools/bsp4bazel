load("//private:sources_aspect.bzl", "SourceFiles", "collect_source_files_aspect")
load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")
load("@io_bazel_rules_scala//scala/private/toolchain_deps:toolchain_deps.bzl", "find_deps_info_on")

def _owner_to_bazel_file(fileLabel):
    workspace = fileLabel.workspace_root
    package = fileLabel.package
    if len(workspace) > 0:
        workspace = workspace + "/"
    return workspace + package

def _bsp_metadata_impl(ctx):
    paths = sorted([f.path for f in ctx.attr.target[SourceFiles].transitive_source_files.to_list()])
    packages = sorted(depset([_owner_to_bazel_file(f.owner) for f in ctx.attr.target[SourceFiles].transitive_source_files.to_list()]).to_list())

    toolchain = ctx.toolchains["@io_bazel_rules_scala//scala:toolchain_type"]

    compile_classpath = [
        file
        for deps in find_deps_info_on(ctx, "@io_bazel_rules_scala//scala:toolchain_type", "scala_compile_classpath").deps
        for file in deps[JavaInfo].compile_jars.to_list()
    ]

    classpath = [file.path for file in ctx.attr.target[JavaInfo].transitive_compile_time_jars.to_list()]
    semanticdb_jars = ctx.attr._semanticdb_scalac_plugin[JavaInfo].transitive_compile_time_jars.to_list()

    json_tpl = """
{{ 
    "scala_version": "{}", 
    "scalac_options": {},
    "classpath": {},
    "scala_compile_jars": {},
    "semanticdb_jars": {},
    "sources": {},
    "packages": {},
    "compile_label": "{}"
}}
"""

    ctx.actions.write(
        ctx.outputs.bsp_target,
        json_tpl.format(
            SCALA_VERSION,
            toolchain.scalacopts,
            classpath,
            [file.path for file in compile_classpath],
            [file.path for file in semanticdb_jars],
            paths,
            packages,
            str(ctx.attr.compile_label.label),
        ),
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(files = [ctx.outputs.bsp_target] + compile_classpath + semanticdb_jars),
    )

bsp_metadata = rule(
    implementation = _bsp_metadata_impl,
    documentation = "Generates a bsp_target.json file for the given target",
    attrs = {
        "target": attr.label(aspects = [collect_source_files_aspect]),
        "compile_label": attr.label(mandatory = True),
        "_semanticdb_scalac_plugin": attr.label(
            default = Label("@io_bazel_rules_scala//scala/private/toolchain_deps:semanticdb_scalac"),
        ),
    },
    toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
    outputs = {
        "bsp_metadata": "bsp_metadata.json",
    },
)

def _bsp_compile_and_gather_impl(ctx):
    jars = [f for f in ctx.attr.target[JavaInfo].transitive_runtime_jars.to_list() if f.is_source == False]

    exec_file = ctx.actions.declare_file("unjar_{}".format(ctx.label.name))
    ctx.actions.expand_template(
        template = ctx.file._template,
        output = exec_file,
        is_executable = True,
        substitutions = {
            "{INPUT_JARS}": " ".join([j.short_path for j in jars]),
            "{OUT_DIR}": ".bsp/.semanticdb",
        },
    )

    return DefaultInfo(
        executable = exec_file,
        runfiles = ctx.runfiles(files = jars),
    )

bsp_compile_and_gather = rule(
    implementation = _bsp_compile_and_gather_impl,
    executable = True,
    attrs = {
        "_template": attr.label(
            default = Label("//private:unzip_jar_metainf.sh.tpl"),
            allow_single_file = True,
        ),
        "target": attr.label(aspects = [collect_source_files_aspect]),
    },
)
