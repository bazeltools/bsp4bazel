load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
load("@io_bazel_rules_scala//scala:providers.bzl", "declare_deps_provider")

scala_toolchain(
    name = "my_toolchain_impl",
    dep_providers = [
        ":my_scala_compile_classpath_provider",
        ":my_scala_library_classpath_provider",
        ":my_scala_macro_classpath_provider",
    ],
    enable_diagnostics_report = True,
    visibility = ["//visibility:public"],
)

toolchain(
    name = "my_scala_toolchain",
    toolchain = "my_toolchain_impl",
    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type",
    visibility = ["//visibility:public"],
)

declare_deps_provider(
    name = "my_scala_compile_classpath_provider",
    deps_id = "scala_compile_classpath",
    visibility = ["//visibility:public"],
    deps = [
        "@io_bazel_rules_scala_scala_compiler",
        "@io_bazel_rules_scala_scala_library",
        "@io_bazel_rules_scala_scala_reflect",
    ],
)

declare_deps_provider(
    name = "my_scala_library_classpath_provider",
    deps_id = "scala_library_classpath",
    deps = [
        "@io_bazel_rules_scala_scala_library",
        "@io_bazel_rules_scala_scala_reflect",
    ],
)

declare_deps_provider(
    name = "my_scala_macro_classpath_provider",
    deps_id = "scala_macro_classpath",
    deps = [
        "@io_bazel_rules_scala_scala_library",
        "@io_bazel_rules_scala_scala_reflect",
    ],
)
