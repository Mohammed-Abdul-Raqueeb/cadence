#!/usr/bin/env bash
#
# Builds, tests and packages Cadence with nothing but the JDK.
#
#   ./build.sh            compile, run the tests
#   ./build.sh jar        also package build/cadence.jar
#   ./build.sh clean      remove build output
#
# There is no Maven or Gradle here on purpose. The project has no
# dependencies, so a build tool would exist only to download the build tool.

set -euo pipefail

# Everything below is run from the project root with *relative* paths, which
# is not a style choice. A Windows `javac` reached through Git Bash reads an
# absolute POSIX path like /c/Users/... as a flag, because `/x` is how flags
# look on Windows, and it splits an @argfile line on the space in a directory
# name. Relative paths have neither a leading slash nor, here, a space.
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The classpath separator is one of the few places the two platforms cannot
# be papered over.
SEP=":"
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) SEP=";" ;;
    *) ;;
esac

CLASSES="build/classes"
TEST_CLASSES="build/test-classes"

# -Xlint:all with -Werror is the closest thing javac has to a lint gate, and
# it is cheaper to keep green from the first commit than to retrofit.
JAVAC_FLAGS=(-Xlint:all -Werror -encoding UTF-8)

# Writes every .java under $1 to the argfile $2, one quoted path per line.
# The quoting is what stops javac splitting a path on a space.
collect_sources() {
    find "$1" -name '*.java' | sed 's/.*/"&"/' > "$2"
}

source_count() {
    wc -l < "$1" | tr -d ' '
}

clean() {
    rm -rf build
    echo "removed build/"
}

compile_main() {
    mkdir -p "$CLASSES"
    collect_sources src/main/java build/main.sources
    javac "${JAVAC_FLAGS[@]}" -d "$CLASSES" "@build/main.sources"
    echo "compiled $(source_count build/main.sources) main sources"
}

compile_tests() {
    mkdir -p "$TEST_CLASSES"
    collect_sources src/test/java build/test.sources
    javac "${JAVAC_FLAGS[@]}" -cp "$CLASSES" -d "$TEST_CLASSES" "@build/test.sources"
    echo "compiled $(source_count build/test.sources) test sources"
}

run_tests() {
    java -cp "$CLASSES$SEP$TEST_CLASSES" dev.raqueeb.cadence.harness.TestRunner "$@"
}

package_jar() {
    jar --create --file build/cadence.jar \
        --main-class dev.raqueeb.cadence.cli.Main \
        -C "$CLASSES" .
    echo "packaged build/cadence.jar"
}

case "${1:-test}" in
    clean)
        clean
        ;;
    compile)
        mkdir -p build
        compile_main
        ;;
    test)
        mkdir -p build
        compile_main
        compile_tests
        shift || true
        run_tests "$@"
        ;;
    jar)
        mkdir -p build
        compile_main
        compile_tests
        run_tests
        package_jar
        ;;
    *)
        echo "usage: build.sh [clean|compile|test|jar]" >&2
        exit 2
        ;;
esac
