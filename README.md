# Bazel BSP

**===**
**NOTE: WIP**
**===**

This is a Bazel BSP Server, optimized to work with [Metals](https://scalameta.org/metals/) from the ground up.

## Installation

Download one of the pre-built binaries, and stick it in your path. Within your Bazel project, run 

```bash
bazel-bsp --setup
```

This will create the nessarily `/.bsp/` config files for Metals to pick up Bazel BSP server. 

## Bazel setup

Setup `scala_rules` to enable diagnostic files to be written. Instructions for this can be found [here](https://github.com/bazelbuild/rules_scala/blob/master/docs/scala_toolchain.md).

Add the bazel-bsp rules to your workspace

```starlark
bazel_bsp_version = "10435c9e50969f66c3527ea19080bdfb7d649f7d"
http_archive(
    name = "bazel-bsp-rules",
    sha256 = "bdf0160186b83b78824ccc2a0c88c93581884c2214e13138cd7a3374f403c2a4",
    strip_prefix = "bazel-bsp-%s" % bazel_bsp_version,
    type = "zip",
    url = "https://github.com/aishfenton/bazel-bsp/archive/%s.zip" % bazel_bsp_version,
)
```

And finally add at least one bsp target (although you can add as many as you like) to specify a project to build. To add a bsp target place a `bsp_target` rule in the `BUILD` files, as so:

```starlark
load("@bazel-bsp-rules//bazel_rules:bsp_target.bzl", "bsp_target")

bsp_target(
    name = "--> a unique name for the bsp target",
    target = "--> the bazel target to trigger",
)
```

# TODO

- [x] Compile provider
- [ ] Generate SemanticDB files ([related](https://github.com/bazelbuild/rules_scala/issues/952))
- [ ] Test provider
- [ ] Run provider
- [ ] Debug provider
- [ ] Make Bazel setup more straight forward