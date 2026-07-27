#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export JAVA_HOME="${JAVA_HOME:-$script_dir/.runtime/jdk/jdk-21.0.12+8}"

if [ "$#" -ge 2 ] && [ "$1" = "-pl" ] && [ "$2" = "bootstrap" ]; then
    shift 2
    set -- -pl :bootstrap "$@"
fi

exec "$script_dir/.runtime/maven/apache-maven-3.9.16/bin/mvn" "$@"
