#!/usr/bin/env sh
# Compiles the browser into build/kotbrowse.jar (needs kotlinc and a JDK 17+).
set -e
mkdir -p build
kotlinc src -include-runtime -d build/kotbrowse.jar
echo "built build/kotbrowse.jar"
