# Bazel BSP

**===**
**NOTE: WIP**
**===**

This is a Bazel BSP Server, optimized to work with [Metals](https://scalameta.org/metals/) from the ground up.

## Installation

Download one of the pre-built binaries, and stick it in your path.

Within your Bazel project, run 

```bash
bazel-bsp --setup
```

This will create the nessarily `.bsp` files for Metals to see there is a Build Server for your project.

### Bazel setup
Bazel BSP requires a few custom Bazel rules

TODO

# TODO

- [x] Compile provider
- [ ] Generate SemanticDB files ([related](https://github.com/bazelbuild/rules_scala/issues/952))
- [ ] Test provider
- [ ] Run provider
- [ ] Debug provider
- [ ] Make Bazel setup more straight forward