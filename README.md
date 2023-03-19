# BSP 4 Bazel
Current Version: [0.0.28](https://github.com/bazeltools/bsp4bazel/releases/tag/0.0.28)

**NOTE: This is still an alpha version. And fairly new. See TODO below for what's still msising**

This is a Bazel BSP Server, optimized to work with [Metals](https://scalameta.org/metals/) from the ground up.

## Installation

Download one of the pre-built binaries, and stick it in your path. Within your Bazel project, run 

```bash
bsp4bazel --setup
```

This will create the nessarily `/.bsp/` config files for Metals to pick up Bazel BSP server. 

## Bazel setup

Setup `scala_rules` to enable diagnostic files to be written. Instructions for this can be found [here](https://github.com/bazelbuild/rules_scala/blob/master/docs/scala_toolchain.md).

Add the bsp4bazel rules to your workspace

```starlark
bsp4bazel_version = "0.0.28"
http_archive(
    name = "bsp4bazel-rules",
    sha256 = "177671b2072646b1e91c74991593eb1e0cf80a75b459ea0d9a1d7e0e0e3bed76",
    strip_prefix = "bazel_rules",
    type = "tar.gz",
    url = "https://github.com/bazeltools/bsp4bazel/releases/download/%s/bazel_rules.tar.gz" % bsp4bazel_version,
)

load("@bsp4bazel-rules//:bsp4bazel_setup.bzl", "bsp4bazel_setup")
bsp4bazel_setup()
```

And finally add at least one bsp target (although you can add as many as you like) to specify a project to build. To add a bsp target place a `bsp_target` rule in the `BUILD` files, as so:

```starlark
load("@bsp4bazel-rules//:bsp_target.bzl", "bsp_target")

bsp_target(
    name = "--> a unique name for the bsp target",
    target = "--> the bazel target to trigger",
)
```

# TODO

- [x] Compile provider
- [x] Make Bazel setup more straight forward
- [ ] Generate SemanticDB files ([related](https://github.com/bazelbuild/rules_scala/pull/1467))
- [ ] Test provider
- [ ] Run provider
- [ ] Debug provider