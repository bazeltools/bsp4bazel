load("@io_bazel_rules_scala//scala:semanticdb_provider.bzl", "SemanticdbInfo")
load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")
load("@io_bazel_rules_scala//scala/private/toolchain_deps:toolchain_deps.bzl", "find_deps_info_on")

def _collect_src_files(srcs):
    return [
        file.path
        for src in srcs
        for file in src.files.to_list()
    ]

def bsp_target_info_aspect_impl(target, ctx):
    """
    This function generates BSP target information for the given target.

    Args:
        target: The target for which to generate BSP target information.
        ctx: The context object.

    Returns:
        A list of OutputGroupInfo objects containing the generated BSP target information.
    """
    
    if SemanticdbInfo in target:
        toolchain = ctx.toolchains["@io_bazel_rules_scala//scala:toolchain_type"]
        classpath = [file.path for file in target[JavaInfo].transitive_compile_time_jars.to_list()]
        compile_classpath = [
            file.path
            for deps in find_deps_info_on(ctx, "@io_bazel_rules_scala//scala:toolchain_type", "scala_compile_classpath").deps
            for file in deps[JavaInfo].compile_jars.to_list()
        ]

        src_files = []
        if hasattr(ctx.rule.attr, "srcs"):
            src_files = _collect_src_files(ctx.rule.attr.srcs)
        if hasattr(ctx.rule.attr, "resources"):
            src_files = src_files + _collect_src_files(ctx.rule.attr.resources)

        output_struct = struct(
            scala_version = SCALA_VERSION,
            scalac_options = toolchain.scalacopts,
            classpath = classpath,
            scala_compiler_jars = compile_classpath,
            srcs = src_files,
            target_label = str(target.label),
            semanticdb_target_root = target[SemanticdbInfo].target_root,
            semanticdb_pluginjar = target[SemanticdbInfo].plugin_jar,
        )

        json_output_file = ctx.actions.declare_file("%s_bsp_target_info.json" % target.label.name)
        ctx.actions.write(json_output_file, json.encode_indent(output_struct))

        return [OutputGroupInfo(json_output_file = depset([json_output_file]))]
    return []

bsp_target_info_aspect = aspect(
    implementation = bsp_target_info_aspect_impl,
    attr_aspects = ["deps"],
    toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
)
