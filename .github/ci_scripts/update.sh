#!/usr/bin/env bash

set -xe

RELEASE=$1
echo "Updating to version $1"

function run() {
    scala-cli .github/ci_scripts/$1.scala -- ${@:2}
}

CURRENT_VERSION=$(run currentVersion build.sbt) 

run updateVersions $CURRENT_VERSION $RELEASE

if [ "$2" == "all" ]
then
    run updateArtifactShas "bazel_rules/bazel_bsp_setup.bzl" README.md ../downloads
fi