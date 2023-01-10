#!/usr/bin/env bash

set -ex

RELEASE=$1
SCRIPT_DIR="./.github/ci_scripts"
BAZEL_RULE="bazel_rules/bazel_bsp_setup.bzl"

if [[ ! "$RELEASE" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Release version wasnt in correct format. Got: $RELEASE"
    exit 1 
fi

CURRENT_VERSION=$(scala-cli $SCRIPT_DIR/currentVersion.scala -- build.sbt)
echo "Updating from release number $CURRENT_VERSION"

# ---
# update build.sbt

# NB: Note not using -i flag as not supported on macos :(

sed "/^val bazelBspVersion/s/$CURRENT_VERSION/$RELEASE/g" build.sbt > build.sbt
cat build.sbt

# ---
# update bazel_rules/bazel_bsp_setup.bzl

sed "/^_bazel_bsp_version/s/$CURRENT_VERSION/$RELEASE/" $BAZEL_RULE > $BAZEL_RULE
cat build.sbt

# ---
# update README.md

sed "s/$CURRENT_VERSION/$RELEASE/g" README.md > README.md
cat build.sbt