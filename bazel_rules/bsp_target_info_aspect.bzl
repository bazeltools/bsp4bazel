load("@io_bazel_rules_scala//scala:semanticdb_provider.bzl", "SemanticdbInfo")

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

        src_files = []
        if hasattr(ctx.rule.attr, "srcs"):
            src_files = _collect_src_files(ctx.rule.attr.srcs)
        if hasattr(ctx.rule.attr, "resources"):
            src_files = src_files + _collect_src_files(ctx.rule.attr.resources)

        output_struct = struct(
            scalac_options = toolchain.scalacopts,
            classpath = classpath,
            srcs = src_files,
            target_label = str(target.label),
            semanticdb_target_root = target[SemanticdbInfo].target_root,
        )

        json_output_file = ctx.actions.declare_file("%s_bsp_target_info.json" % target.label.name)
        ctx.actions.write(json_output_file, json.encode_indent(output_struct))

        return [
            OutputGroupInfo(
                bsp_output = depset([json_output_file]),
            ),
        ]

    return []

bsp_target_info_aspect = aspect(
    implementation = bsp_target_info_aspect_impl,
    attr_aspects = ["deps"],
    toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
)
