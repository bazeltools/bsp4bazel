load("@io_bazel_rules_scala//scala/private/toolchain_deps:toolchain_deps.bzl", "find_deps_info_on")
load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")

def _copy_files(ctx, files):
    output_files = []

    for file in files:
        output_file = ctx.actions.declare_file(ctx.attr.name + "_" + file.basename)

        ctx.actions.run_shell(
            inputs = [file],
            outputs = [output_file],
            command = "cp {src} {out}".format(src = file.path, out = output_file.path),
        )

        output_files.append(output_file)

    return output_files

def _bsp_workspace_info_impl(ctx):
    """
    Outputs metadata about the BSP workspace, as well as copying depenendies to output (so that downstream tools can use them)

    Args:
        ctx: The context object.

    Returns:
        A list of dependencies for the BSP workspace.
    """

    compile_classpath = [
        file
        for deps in find_deps_info_on(ctx, "@io_bazel_rules_scala//scala:toolchain_type", "scala_compile_classpath").deps
        for file in deps[JavaInfo].compile_jars.to_list()
    ]

    # Why not use the class_jar in SemanticdbInfo? Because it's a string, not a File, so we can't pass
    # pass it to outputs.
    # https://github.com/bazelbuild/rules_scala/issues/1527
    semanticdb_classpath = [
        file
        for deps in find_deps_info_on(ctx, "@io_bazel_rules_scala//scala:toolchain_type", "semanticdb").deps
        for file in deps[JavaInfo].compile_jars.to_list()
    ]

    scalac_output_files = _copy_files(ctx, compile_classpath)
    semanticdb_output_files = _copy_files(ctx, semanticdb_classpath)

    json_output_file = ctx.actions.declare_file(ctx.attr.name + ".json")
    output_struct = struct(
        scala_version = SCALA_VERSION,
        scalac_deps = [file.path for file in scalac_output_files],
        semanticdb_dep = [file.path for file in semanticdb_output_files][0],
    )
    ctx.actions.write(json_output_file, json.encode_indent(output_struct))

    return [DefaultInfo(files = depset(scalac_output_files + semanticdb_output_files + [json_output_file]))]

bsp_workspace_info = rule(
    implementation = _bsp_workspace_info_impl,
    toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
)
