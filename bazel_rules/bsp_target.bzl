load("//private:bsp_target_rules.bzl", "bsp_metadata", "bsp_compile_and_gather")

def bsp_target(name, target):
    compile_label = "bsp_compile_and_gather_{}".format(name)

    bsp_metadata(
        name = "bsp_metadata_{}".format(name),
        compile_label = compile_label,
        target = target
    )

    bsp_compile_and_gather(
        name = compile_label, 
        target = target
    )
