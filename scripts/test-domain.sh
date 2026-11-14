#!/usr/bin/env bash
# Android-free tests for segment editing, history, cache keys and project JSON.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
TOOLS_DIR="${AUDIOASSISTANT_TEST_TOOLS:-$PROJECT_DIR/.test-tools}"
OFFLINE=false
case "${1:-}" in
    --offline) OFFLINE=true ;;
    --help|-h) printf 'Usage: scripts/test-domain.sh [--offline]\nRequires Java 17+ and curl. Downloads only pinned Maven Central JVM test tools.\n'; exit 0 ;;
    "") ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || exit 2
command -v java >/dev/null || { printf 'Java 17+ is required.\n' >&2; exit 1; }
mkdir -p "$TOOLS_DIR/jars"
JAR_DIR="$(cd -- "$TOOLS_DIR/jars" && pwd)"
sha256_file() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d ' ' -f 1
    else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}
classpath=''
while read -r filename expected url; do
    [[ -z "$filename" || "$filename" == \#* ]] && continue
    path="$JAR_DIR/$filename"
    if [[ ! -f "$path" ]]; then
        [[ "$OFFLINE" != true ]] || { printf 'Missing offline tool: %s\n' "$filename" >&2; exit 1; }
        curl --fail --location --show-error --retry 3 --connect-timeout 15 \
            "$url" --output "$path.part"
        [[ "$(sha256_file "$path.part")" == "$expected" ]] || {
            printf 'Checksum mismatch: %s\n' "$filename" >&2; exit 1;
        }
        mv "$path.part" "$path"
    fi
    [[ "$(sha256_file "$path")" == "$expected" ]] || {
        printf 'Cached tool checksum mismatch: %s; remove it and retry.\n' "$path" >&2; exit 1;
    }
    classpath="${classpath:+$classpath:}$path"
done < "$SCRIPT_DIR/jvm-test-tools.lock"

BUILD_DIR="$(mktemp -d "$TOOLS_DIR/classes.XXXXXX")"
trap 'rm -rf -- "$BUILD_DIR"' EXIT
sources=("$PROJECT_DIR"/app/src/main/java/com/audioassistant/domain/*.kt)
sources+=("$PROJECT_DIR"/app/src/main/java/com/audioassistant/data/AudioLibraryStore.kt)
tests=("$PROJECT_DIR"/app/src/test/java/com/audioassistant/domain/*Test.kt)
classes=()
for test in "${tests[@]}"; do
    name="${test##*/}"
    classes+=("com.audioassistant.domain.${name%.kt}")
done
java -Xmx1g -cp "$JAR_DIR/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$classpath" \
    "-Xplugin=$JAR_DIR/kotlin-serialization-compiler-plugin-embeddable-2.1.20.jar" \
    -d "$BUILD_DIR" "${sources[@]}" "${tests[@]}"
java -cp "$BUILD_DIR:$classpath" org.junit.runner.JUnitCore "${classes[@]}"
