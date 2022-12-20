SourceFiles = provider(
    fields = {
        "transitive_source_files": "list of transitive source files of a target",
    },
)

#add 'resources' ? if so _accumulate_transitive_config_files needs to check for dep in deps if ConfigFiles in dep
SourceAttr = ["tars", "deps", "runtime_deps", "exports"]

def _accumulate_transitive_source_files(accumulated, deps):
    return depset(
        transitive = [dep[SourceFiles].transitive_source_files for dep in deps] + [accumulated],
    )

def _collect_current_source_files(srcs):
    return [
        file
        for src in srcs
        for file in src.files.to_list()
    ]

def _collect_source_files_aspect_impl(target, ctx):
    current_source_files = []
    if hasattr(ctx.rule.attr, "srcs"):
        current_source_files = _collect_current_source_files(ctx.rule.attr.srcs)
    if hasattr(ctx.rule.attr, "resources"):
        current_source_files = current_source_files + _collect_current_source_files(ctx.rule.attr.resources)

    accumulated_source_files = depset(current_source_files)
    for attr in SourceAttr:
        if hasattr(ctx.rule.attr, attr):
            accumulated_source_files = _accumulate_transitive_source_files(accumulated_source_files, getattr(ctx.rule.attr, attr))

    return [SourceFiles(transitive_source_files = accumulated_source_files)]

collect_source_files_aspect = aspect(
    implementation = _collect_source_files_aspect_impl,
    attr_aspects = SourceAttr,
)
