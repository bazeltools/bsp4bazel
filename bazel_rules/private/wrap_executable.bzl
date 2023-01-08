# When bazel fetches/downloads binaries pre-built elsewhere it won't natively think of them as a binary to itself.
# This rule simply copies things from the input to the output only, but then the output has the runfiles setup to call this an executable.

wrapping_script = """
#!/bin/bash
set -e

ORIGINAL_PWD="$(pwd)"
EXEC_PATH="{exec_path}"
if [ -f "$ORIGINAL_PWD/$EXEC_PATH" ]; then
    EXEC_PATH="$ORIGINAL_PWD/$EXEC_PATH"
fi
# Bazel sets this env var when you do bazel run
# the default CWD from bazel is somewhere in the output tree
# but this is never a useful default.
if [ -n "$BUILD_WORKING_DIRECTORY" ]; then
    cd $BUILD_WORKING_DIRECTORY
fi

exec $EXEC_PATH "$@"
"""

def _wrap_executable_test_impl(ctx):
    bin_file = ctx.actions.declare_file("bin")
    script = ctx.actions.declare_file("script.sh")
    input_file = ctx.files.executable_path[0]

    ctx.actions.run_shell(
        inputs = depset([input_file]),
        outputs = [bin_file],
        command = "cp {} {} && chmod +x {}".format(input_file.path, bin_file.path, bin_file.path),
        mnemonic = "WrapExecutable",
        progress_message = "WrapExecutable {}".format(ctx.label),
    )

    ctx.actions.write(script, wrapping_script.format(exec_path = bin_file.short_path), is_executable = True)

    return [DefaultInfo(
        executable = script,
        files = depset([bin_file, script]),
        runfiles = ctx.runfiles(files = [script, bin_file]),
    )]

wrap_executable = rule(
    attrs = {
        "executable_path": attr.label(allow_single_file = True),
    },
    doc = "Wrap a single executable.",
    executable = True,
    implementation = _wrap_executable_test_impl,
)
