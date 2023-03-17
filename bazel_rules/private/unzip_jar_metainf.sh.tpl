#!/usr/bin/env bash

# Each argument is a Jar to be unzipped. 
jars=({INPUT_JARS})
out_dir="$BUILD_WORKSPACE_DIRECTORY/{OUT_DIR}"
ttl_days={TTL_DAYS}

mkdir -p "$out_dir"

for jar in "${jars[@]}"
do
    sha_file="$out_dir/META-INF/semanticdb/$jar.sha"
    echo "SHA $sha_file"
    if sha256sum -c "$sha_file"; then
        echo "skipping $jar, already up to date"
    else
        unzip -o "$jar" "META-INF/*" -d "$out_dir"
        sha256sum $jar > "$sha_file"
    fi 
done