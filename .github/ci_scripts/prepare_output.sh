#!/usr/bin/env bash

set -ex

INPUT_FILE=$1
OUTPUT_PATH="`pwd`/$2"
OUTPUT_NAME=$3

if [ ! -d $OUTPUT_PATH ]; then
    mkdir $OUTPUT_PATH
fi

mv $INPUT_FILE $OUTPUT_PATH/$OUTPUT_NAME

GENERATED_SHA_256=$(shasum -a 256 $OUTPUT_PATH/$OUTPUT_NAME | awk '{print $1}')
echo $GENERATED_SHA_256 > $OUTPUT_PATH/${OUTPUT_NAME}.sha256