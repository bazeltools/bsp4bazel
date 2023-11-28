SrcsProvider = provider(
    doc = "Provides a list of source files of a target",
    fields = {
        "src_files": "list of transitive source files of a target",
    },
)

def _srcs_collector_aspect_impl(target, ctx):
    # This function is called for each target the aspect is applied to.
    srcs = []
    if hasattr(ctx.rule.attr, "srcs"):
        # Collect the sources if the target has a 'srcs' attribute
        for src in ctx.rule.attr.srcs:
            srcs.extend(src.files.to_list())
    # Return the collected sources in a dictionary
    return [SrcsProvider(src_files=srcs)]

srcs_collector_aspect = aspect(
    implementation = _srcs_collector_aspect_impl,
    attr_aspects = ["deps"],  # Adjust this if you want to traverse different attributes
)
