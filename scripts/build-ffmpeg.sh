#!/usr/bin/env bash
# Build only FFmpeg shared audio libraries; never an Android package or FFmpeg CLI.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=ffmpeg.lock
source "$SCRIPT_DIR/ffmpeg.lock"
WORK_DIR="${FFMPEG_WORK_DIR:-$PROJECT_DIR/.ffmpeg-build}"
OUTPUT_DIR="${FFMPEG_OUTPUT_DIR:-$PROJECT_DIR/app/src/main/ffmpeg/arm64-v8a}"
# NDK r28c exposes native APIs through 35; AGP also clamps minSdk 36 to this.
# The application's manifest/Gradle minSdk remains 36 (Android 16 only).
ANDROID_API=35

component_flags=(
    --disable-everything --disable-autodetect --disable-network
    --disable-programs --disable-doc --disable-debug --disable-stripping
    --disable-gpl --disable-nonfree --disable-version3
    --disable-static --enable-shared --enable-pic --enable-small
    --disable-avdevice --disable-swscale --disable-postproc
    --enable-avutil --enable-avcodec --enable-avformat --enable-avfilter --enable-swresample
    --enable-protocol=file
    --enable-demuxer=aac,ac3,eac3,mp3,flac,ogg,wav,mov,matroska,mpegts,mpegtsraw,mpegps,avi,aiff,caf
    --enable-decoder=aac,aac_latm,mp2,mp2float,mp3,mp3float,flac,alac,vorbis,opus,ac3,eac3,pcm_u8,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be,pcm_alaw,pcm_mulaw,adpcm_ima_wav,adpcm_ms
    --enable-parser=aac,aac_latm,mpegaudio,flac,vorbis,opus,ac3
    --enable-encoder=aac
    --enable-muxer=ipod,mp4
    --enable-bsf=aac_adtstoasc,extract_extradata
    --enable-filter=abuffer,abuffersink,loudnorm,aresample,atrim,asetpts,concat,volume,afade,aformat
)

usage() {
    cat <<'USAGE'
Usage: scripts/build-ffmpeg.sh [--help | --print-config | --download-only]

Default: build arm64-v8a shared libraries with native API 35, using NDK r28c+.
The app's compileSdk/minSdk/targetSdk remain 36 (Android 16+).
ANDROID_NDK_HOME points to the NDK (or set ANDROID_NDK_ROOT).
FFMPEG_WORK_DIR, FFMPEG_OUTPUT_DIR and FFMPEG_JOBS are optional overrides.
--print-config prints the pinned source and components without downloading/building.
--download-only verifies and retains the original upstream source archive.
This script does not build an APK/AAB. It never enables GPL/nonfree code.
USAGE
}

MODE=build
case "${1:-}" in
    --help|-h) usage; exit 0 ;;
    --print-config) MODE=config ;;
    --download-only) MODE=download ;;
    "") ;;
    *) usage >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { usage >&2; exit 2; }
if [[ "$MODE" == config ]]; then
    printf 'FFmpeg %s\nSource: %s\nSHA-256: %s\nABI: arm64-v8a\nNative API: %s\nApp minSdk: 36\n' \
        "$FFMPEG_VERSION" "$FFMPEG_URL" "$FFMPEG_SHA256" "$ANDROID_API"
    printf '%s\n' "${component_flags[@]}"
    exit 0
fi

for tool in curl tar make; do
    command -v "$tool" >/dev/null || { printf 'Required tool missing: %s\n' "$tool" >&2; exit 1; }
done
sha256_file() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d ' ' -f 1
    else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}
mkdir -p "$WORK_DIR"
WORK_DIR="$(cd -- "$WORK_DIR" && pwd)"
ARCHIVE="$WORK_DIR/ffmpeg-$FFMPEG_VERSION.tar.xz"
if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --show-error --retry 3 --connect-timeout 15 \
        "$FFMPEG_URL" --output "$ARCHIVE.part"
    [[ "$(sha256_file "$ARCHIVE.part")" == "$FFMPEG_SHA256" ]] || {
        printf 'FFmpeg archive checksum mismatch; refusing to extract.\n' >&2; exit 1;
    }
    mv "$ARCHIVE.part" "$ARCHIVE"
fi
[[ "$(sha256_file "$ARCHIVE")" == "$FFMPEG_SHA256" ]] || {
    printf 'Cached FFmpeg archive checksum mismatch; remove it and retry.\n' >&2; exit 1;
}
printf 'Verified source: %s\n' "$ARCHIVE"
[[ "$MODE" != download ]] || exit 0

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[[ -n "$NDK_DIR" && -f "$NDK_DIR/source.properties" ]] || {
    printf 'Set ANDROID_NDK_HOME to an installed Android NDK r28c or newer.\n' >&2; exit 1;
}
NDK_VERSION="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$NDK_DIR/source.properties" | tr -d '\r')"
NDK_MAJOR="${NDK_VERSION%%.*}"
[[ "$NDK_MAJOR" =~ ^[0-9]+$ && "$NDK_MAJOR" -ge 28 ]] || {
    printf 'NDK r28+ required for the 16 KiB native library baseline (found %s).\n' "$NDK_VERSION" >&2; exit 1;
}
case "$(uname -s)" in
    Linux) HOST_TAG=linux-x86_64 ;;
    Darwin) HOST_TAG=darwin-x86_64 ;;
    *) printf 'Use Linux or macOS (Windows: WSL2 with the Linux NDK).\n' >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
[[ -x "$CC" && -x "$CXX" ]] || {
    printf 'This NDK has no native API %s arm64 compiler. Install NDK 28.2.13676358.\n' "$ANDROID_API" >&2; exit 1;
}
JOBS="${FFMPEG_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf 4)}"
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || { printf 'FFMPEG_JOBS must be positive.\n' >&2; exit 2; }
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd)"
# A fresh extraction and build directory prevent stale configuration or local patches.
BUILD_ROOT="$(mktemp -d "$WORK_DIR/build.XXXXXX")"
tar -xJf "$ARCHIVE" -C "$BUILD_ROOT"
SOURCE_DIR="$BUILD_ROOT/ffmpeg-$FFMPEG_VERSION"
mkdir -p "$BUILD_ROOT/obj" "$BUILD_ROOT/artifacts"
configure_args=(
    "--prefix=$OUTPUT_DIR" --target-os=android --arch=aarch64 --cpu=armv8-a
    --enable-cross-compile "--sysroot=$TOOLCHAIN/sysroot"
    "--cc=$CC" "--cxx=$CXX" "--ar=$TOOLCHAIN/bin/llvm-ar"
    "--nm=$TOOLCHAIN/bin/llvm-nm" "--ranlib=$TOOLCHAIN/bin/llvm-ranlib"
    "--strip=$TOOLCHAIN/bin/llvm-strip"
    '--extra-cflags=-O2 -fPIC'
    '--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
    "${component_flags[@]}"
)
printf '%q ' "$SOURCE_DIR/configure" "${configure_args[@]}" > "$BUILD_ROOT/artifacts/configure-command.sh"
printf '\n' >> "$BUILD_ROOT/artifacts/configure-command.sh"
(
    cd "$BUILD_ROOT/obj"
    "$SOURCE_DIR/configure" "${configure_args[@]}" 2>&1 | tee "$BUILD_ROOT/artifacts/configure-output.txt"
    cp ffbuild/config.log config.h config_components.h ffbuild/config.mak "$BUILD_ROOT/artifacts/"
    make -j "$JOBS" 2>&1 | tee "$BUILD_ROOT/artifacts/build.log"
    make install 2>&1 | tee "$BUILD_ROOT/artifacts/install.log"
)

# Preserve exact input, configuration and licensing with the built libraries.
mkdir -p "$OUTPUT_DIR/build-info" "$OUTPUT_DIR/licenses"
cp "$SCRIPT_DIR/ffmpeg.lock" "$SCRIPT_DIR/build-ffmpeg.sh" "$OUTPUT_DIR/build-info/"
cp -R "$BUILD_ROOT/artifacts/." "$OUTPUT_DIR/build-info/"
printf 'NDK=%s\nAPI=%s\nABI=arm64-v8a\n' "$NDK_VERSION" "$ANDROID_API" > "$OUTPUT_DIR/build-info/toolchain.txt"
cp "$SOURCE_DIR/COPYING.LGPLv2.1" "$SOURCE_DIR/LICENSE.md" "$OUTPUT_DIR/licenses/"
for lib in avutil avcodec avformat avfilter swresample; do
    library="$OUTPUT_DIR/lib/lib${lib}.so"
    [[ -f "$library" ]] || { printf 'Missing installed library: %s\n' "$library" >&2; exit 1; }
    "$TOOLCHAIN/bin/llvm-readelf" -lW "$library" >> "$OUTPUT_DIR/build-info/elf-program-headers.txt"
    "$TOOLCHAIN/bin/llvm-readelf" -dW "$library" >> "$OUTPUT_DIR/build-info/elf-dynamic.txt"
    # All PT_LOAD segments must have at least 0x4000 (16 KiB) alignment.
    "$TOOLCHAIN/bin/llvm-readelf" -lW "$library" | awk '
        function hex(s, value, i) {
            sub(/^0x/, "", s); value=0
            for (i=1; i<=length(s); i++) value=value*16+index("0123456789abcdef", substr(tolower(s), i, 1))-1
            return value
        }
        $1 == "LOAD" { found=1; if (hex($NF) < 16384) bad=1 }
        END { exit (!found || bad) }' || {
        printf 'Unexpected ELF LOAD alignment in %s; inspect build-info.\n' "$library" >&2; exit 1;
    }
done
printf 'Installed FFmpeg audio libraries: %s\nBuild logs: %s\nSource archive: %s\n' \
    "$OUTPUT_DIR" "$OUTPUT_DIR/build-info" "$ARCHIVE"
