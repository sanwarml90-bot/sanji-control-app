#!/bin/sh
# Gradle wrapper for Sanji Control.
# Uses the installed Gradle 8.5 in-process (see build.sh) because the
# Gradle daemon's socket protocol fails in this VM.
exec "$(dirname "$0")/build.sh" "$@"
