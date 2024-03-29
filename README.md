# BSP 4 Bazel
Current Version: [0.0.30](https://github.com/bazeltools/bsp4bazel/releases/tag/0.0.30)

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
bsp4bazel_version = "0.0.30"
http_archive(
    name = "bsp4bazel-rules",
    sha256 = "b8ea773d614799c3bc58907eea418beb07a9fbba8ec4c1a6ce46309d1f9d828a",
    strip_prefix = "bazel_rules",
    type = "tar.gz",
    url = "https://github.com/bazeltools/bsp4bazel/releases/download/%s/bazel_rules.tar.gz" % bsp4bazel_version,
)

load("@bsp4bazel-rules//:bsp4bazel_setup.bzl", "bsp4bazel_setup")
bsp4bazel_setup()
```

# TODO

- [x] Compile provider
- [x] Make Bazel setup more straight forward
- [x] Generate SemanticDB files ([related](https://github.com/bazelbuild/rules_scala/pull/1467))
- [ ] Test provider
- [ ] Run provider
- [ ] Debug provider