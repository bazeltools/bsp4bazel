load("//bazel_rules:sources_aspect.bzl", "SourceFiles", "collect_source_files_aspect")

def _owner_to_bazel_file(fileLabel):
    workspace = fileLabel.workspace_root
    package = fileLabel.package
    if len(workspace) > 0:
        workspace = workspace + "/"
    if len(package) > 0:
        package = package + "/"
    return workspace + package + "BUILD.bazel"

def _collect_source_files_rule_impl(ctx):
    paths = sorted([f.path for f in ctx.attr.target[SourceFiles].transitive_source_files.to_list()])
    owners = sorted(depset([_owner_to_bazel_file(f.owner) for f in ctx.attr.target[SourceFiles].transitive_source_files.to_list()] + [_owner_to_bazel_file(ctx.label)]).to_list())

    ctx.actions.write(ctx.outputs.source_files, "{ \"sources\": %s, \"buildFiles\": %s}" % (paths, owners))
    return DefaultInfo(
        runfiles = ctx.runfiles(files = [ctx.outputs.source_files]),
    )

source_files = rule(
    implementation = _collect_source_files_rule_impl,
    attrs = {
        "target": attr.label(aspects = [collect_source_files_aspect]),
    },
    outputs = {"source_files": "sources.json"},
)
