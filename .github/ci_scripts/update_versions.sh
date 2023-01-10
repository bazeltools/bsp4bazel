#!/usr/bin/env bash

set -ex

RELEASE=$1
SCRIPT_DIR="./.github/ci_scripts"
BAZEL_RULE="bazel_rules/bazel_bsp_setup.bzl"

SED="scala-cli ./.github/ci_scripts/sed.scala --"

if [[ ! "$RELEASE" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Release version wasnt in correct format. Got: $RELEASE"
    exit 1 
fi

CURRENT_VERSION=$(scala-cli $SCRIPT_DIR/currentVersion.scala -- build.sbt)
echo "Updating from release number $CURRENT_VERSION"

# ---
# update build.sbt

# NB: Note not using -i flag as not supported on macos :(

$SED true "^val bazelBspVersion" $CURRENT_VERSION $RELEASE build.sbt

# ---
# update bazel_rules/bazel_bsp_setup.bzl

$SED true "^_bazel_bsp_version $CURRENT_VERSION $RELEASE $BAZEL_RULE

# ---
# update README.md

$SED true "." $CURRENT_VERSION $RELEASE README.md 