# Bazel BSP
Current Version: [0.0.24](https://github.com/aishfenton/bazel-bsp/releases/tag/0.0.24)

**NOTE: This is still an alpha version. And fairly new. See TODO below for what's still msising**

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
bazel_bsp_version = "0.0.24"
http_archive(
    name = "bazel-bsp-rules",
    sha256 = "ebabf269eabe9744bdfb57dcb9835e465f4519030bee69bb1b25c15ae5b13156",
    strip_prefix = "bazel_rules",
    type = "tar.gz",
    url = "https://github.com/aishfenton/bazel-bsp/releases/download/{}/bazel_rules.tar.gz" % bazel_bsp_version,
)

load("@bazel-bsp-rules//:bazel_bsp_setup.bzl", "bazel_bsp_setup")
bazel_bsp_setup()
```

And finally add at least one bsp target (although you can add as many as you like) to specify a project to build. To add a bsp target place a `bsp_target` rule in the `BUILD` files, as so:

```starlark
load("@bazel-bsp-rules//:bsp_target.bzl", "bsp_target")

bsp_target(
    name = "--> a unique name for the bsp target",
    target = "--> the bazel target to trigger",
)
```

# TODO

- [x] Compile provider
- [x] Make Bazel setup more straight forward
- [ ] Generate SemanticDB files ([related](https://github.com/bazelbuild/rules_scala/issues/952))
- [ ] Test provider
- [ ] Run provider
- [ ] Debug provider