#!/usr/bin/env sh
# Builds the test suite against build/kotbrowse.jar and runs it.
set -e
[ -f build/kotbrowse.jar ] || sh build.sh
kotlinc -cp build/kotbrowse.jar test -d build/tests.jar
java -Djava.awt.headless=true -cp "build/kotbrowse.jar:build/tests.jar" kotbrowse.TestsKt
