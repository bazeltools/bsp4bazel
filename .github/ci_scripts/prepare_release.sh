#!/usr/bin/env bash

set -ex

RELEASE = $1
PREV_RELEASE=$(git describe --abbrev=0)
REPO_DIR = "repo"
DOWNLOAD_DIR = "downloads"

# ---
# update build.sbt (version)
sed -i "/version/s/$PREV_RELEASE/$RELEASE/" $REPO_DIR/build.sbt 

# ---
# update bazel_rules (version, file shas)

BAZEL_RULE = $REPO_DIR/bazel_rules/bazel_bsp_setup.bzl

sed -i "/^_version/s/$PREV_RELEASE/$RELEASE/" $BAZEL_RULE 

update_sha () {
    local arch = $1
    local new_sha = $(cat ${DOWNLOAD_DIR}/bazel-bsp-$arch.sha256)
    sed -i "${arch}\":/s/\".*\"$/$new_sha/" $BAZEL_RULE 
}

update_sha "linux-x86"
update_sha "macos-x86"

# ---
# update readme version, http rule

sed -i "s/$PREV_RELEASE/$RELEASE/g" $REPO_DIR/README.md
