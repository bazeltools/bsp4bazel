#!/usr/bin/env bash

set -ex

RELEASE=$1
PREV_RELEASE=$(git describe --abbrev=0 --tags | sed 's/^v//')
DOWNLOAD_DIR="../downloads"

# ---
# update build.sbt (version)
sed -i "/version/s/$PREV_RELEASE/$RELEASE/g" build.sbt 

# ---
# update bazel_rules (version, file shas)

BAZEL_RULE=bazel_rules/bazel_bsp_setup.bzl

sed -i "/^_version/s/$PREV_RELEASE/$RELEASE/" $BAZEL_RULE 

function update_sha {
    local arch=$1
    local new_sha=$(cat ${DOWNLOAD_DIR}/bazel-bsp-$arch.sha256)
    sed -i "/${arch}\":/s/\"[a-z0-9]\+\",\?$/\"${new_sha}\",/" $BAZEL_RULE 
}

update_sha "linux-x86"
update_sha "macos-x86"

# ---
# update readme version, http rule

sed -i "s/$PREV_RELEASE/$RELEASE/g" README.md
