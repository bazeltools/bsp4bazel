#!/usr/bin/env bash

set -xe

RELEASE=$1
SCRIPT_DIR=".github/ci_scripts"

echo "Updating to version $1"

CURRENT_VERSION=$(scala-cli $SCRIPT_DIR/currentVersion.scala -- build.sbt) 
echo "Current version is $CURRENT_VERSION"

scala-cli $SCRIPT_DIR/updateVersions.scala -- $CURRENT_VERSION $RELEASE

if [ "$2" = "all" ]
then
    scala-cli $SCRIPT_DIR/updateArtifactShas.scala -- "bazel_rules/bazel_bsp_setup.bzl" README.md ../downloads
fi