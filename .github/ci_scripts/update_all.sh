#!/usr/bin/env bash
set -xe

RELEASE=$1
echo "Updating to version $1"

SCRIPT_DIR=".github/ci_scripts"
STAGING_DIR="../staging"

CURRENT_VERSION=$(scala-cli $SCRIPT_DIR/currentVersion.scala -- build.sbt) 
echo "Current version is $CURRENT_VERSION"

# Update versions in files
scala-cli $SCRIPT_DIR/updateVersion.scala -- README.md "[0-9]+\.[0.9]+\.[0-9]+" $RELEASE 
scala-cli $SCRIPT_DIR/updateVersion.scala -- build.sbt "^val bsp4BazelVersion" $RELEASE 
scala-cli $SCRIPT_DIR/updateVersion.scala -- bazel_rules/bsp4bazel_setup.bzl "^_bsp4bazel_version" $RELEASE  

# Update artifact SHAs in bazel rule
scala-cli $SCRIPT_DIR/updateArtifactShas.scala --main-class updateBazelRule -- $STAGING_DIR 

# Tar bazel rules, and stage (with Sha256)
tar -czvf bazel_rules.tar.gz bazel_rules/
$SCRIPT_DIR/prepare_output.sh bazel_rules.tar.gz $STAGING_DIR bazel_rules.tar.gz 

# Update artifact SHAs in README
scala-cli $SCRIPT_DIR/updateArtifactShas.scala --main-class updateReadme -- $STAGING_DIR 
