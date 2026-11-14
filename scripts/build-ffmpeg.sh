#!/usr/bin/env bash
# Build FFmpeg shared audio libraries with static PIC LAME; never an APK or CLI.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=ffmpeg.lock
source "$SCRIPT_DIR/ffmpeg.lock"
# shellcheck source=lame.lock
source "$SCRIPT_DIR/lame.lock"
WORK_DIR="${FFMPEG_WORK_DIR:-$PROJECT_DIR/.ffmpeg-build}"
OUTPUT_DIR="${FFMPEG_OUTPUT_DIR:-$PROJECT_DIR/app/src/main/ffmpeg/arm64-v8a}"
# NDK r28c exposes native APIs through 35; AGP also clamps minSdk 36 to this.
# The application's manifest/Gradle minSdk remains 36 (Android 16 only).
ANDROID_API=35

lame_component_flags=(
    --enable-static --disable-shared --with-pic --disable-frontend
    --disable-decoder --disable-analyzer-hooks --disable-nasm --disable-gtktest
    --disable-mp3x --disable-mp3rtp --disable-rpath --with-fileio=lame
)

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
    --enable-libmp3lame --enable-encoder=aac,libmp3lame
    --enable-muxer=ipod,mp4,mp3
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
--download-only verifies and retains both original upstream source archives.
LAME is built as an encode-only PIC static library inside FFmpeg's shared codec
library; no LAME executable, decoder or separate shared library is installed.
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
    printf '\nLAME %s\nSource: %s\nSHA-256: %s\n' "$LAME_VERSION" "$LAME_URL" "$LAME_SHA256"
    printf '%s\n' "${lame_component_flags[@]}"
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
download_verified() {
    local archive="$1" url="$2" expected="$3"
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --show-error --retry 3 --connect-timeout 15 \
            "$url" --output "$archive.part"
        [[ "$(sha256_file "$archive.part")" == "$expected" ]] || {
            printf 'Source archive checksum mismatch; refusing to extract: %s\n' "$archive" >&2; exit 1;
        }
        mv "$archive.part" "$archive"
    fi
    [[ "$(sha256_file "$archive")" == "$expected" ]] || {
        printf 'Cached source archive checksum mismatch; remove it and retry: %s\n' "$archive" >&2; exit 1;
    }
    printf 'Verified source: %s\n' "$archive"
}
ARCHIVE="$WORK_DIR/ffmpeg-$FFMPEG_VERSION.tar.xz"
LAME_ARCHIVE="$WORK_DIR/lame-$LAME_VERSION.tar.gz"
download_verified "$ARCHIVE" "$FFMPEG_URL" "$FFMPEG_SHA256"
download_verified "$LAME_ARCHIVE" "$LAME_URL" "$LAME_SHA256"
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
tar -xzf "$LAME_ARCHIVE" -C "$BUILD_ROOT"
SOURCE_DIR="$BUILD_ROOT/ffmpeg-$FFMPEG_VERSION"
LAME_SOURCE_DIR="$BUILD_ROOT/lame-$LAME_VERSION"
LAME_BUILD_DIR="$BUILD_ROOT/lame-obj"
LAME_PREFIX="$BUILD_ROOT/lame-install"
mkdir -p "$BUILD_ROOT/obj" "$BUILD_ROOT/artifacts/lame" "$LAME_BUILD_DIR"
lame_configure_args=(
    "--prefix=$LAME_PREFIX" --host=aarch64-linux-android
    "--build=$("$LAME_SOURCE_DIR/config.guess")" "${lame_component_flags[@]}"
)
# Keep the complete cross-build command, including the environment, for relinking.
printf '%q ' env "CC=$CC" "AR=$TOOLCHAIN/bin/llvm-ar" "RANLIB=$TOOLCHAIN/bin/llvm-ranlib" \
    "NM=$TOOLCHAIN/bin/llvm-nm" "STRIP=$TOOLCHAIN/bin/llvm-strip" \
    'CFLAGS=-O2 -fPIC' \
    'LDFLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384' \
    "$LAME_SOURCE_DIR/configure" "${lame_configure_args[@]}" > "$BUILD_ROOT/artifacts/lame/configure-command.sh"
printf '\n' >> "$BUILD_ROOT/artifacts/lame/configure-command.sh"
(
    cd "$LAME_BUILD_DIR"
    CC="$CC" AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
    NM="$TOOLCHAIN/bin/llvm-nm" STRIP="$TOOLCHAIN/bin/llvm-strip" \
    CFLAGS='-O2 -fPIC' \
    LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384' \
        "$LAME_SOURCE_DIR/configure" "${lame_configure_args[@]}" \
        2>&1 | tee "$BUILD_ROOT/artifacts/lame/configure-output.txt"
    # The bundled mpg123 decoder is excluded; only the LGPL encoder is built.
    if sed -n '/^#define HAVE_MPGLIB 1/p; /^#define DECODE_ON_THE_FLY 1/p' config.h | \
        awk 'END { exit (NR == 0) }'; then
        printf 'Unexpected LAME decoder configuration; refusing to continue.\n' >&2; exit 1
    fi
    cp config.log config.h config.status Makefile "$BUILD_ROOT/artifacts/lame/"
    make -j "$JOBS" 2>&1 | tee "$BUILD_ROOT/artifacts/lame/build.log"
    make install 2>&1 | tee "$BUILD_ROOT/artifacts/lame/install.log"
)
[[ -f "$LAME_PREFIX/lib/libmp3lame.a" && -f "$LAME_PREFIX/include/lame/lame.h" ]] || {
    printf 'Missing LAME static library/header.\n' >&2; exit 1;
}
configure_args=(
    "--prefix=$OUTPUT_DIR" --target-os=android --arch=aarch64 --cpu=armv8-a
    --enable-cross-compile "--sysroot=$TOOLCHAIN/sysroot"
    "--cc=$CC" "--cxx=$CXX" "--ar=$TOOLCHAIN/bin/llvm-ar"
    "--nm=$TOOLCHAIN/bin/llvm-nm" "--ranlib=$TOOLCHAIN/bin/llvm-ranlib"
    "--strip=$TOOLCHAIN/bin/llvm-strip"
    "--extra-cflags=-O2 -fPIC -I$LAME_PREFIX/include"
    "--extra-ldflags=-L$LAME_PREFIX/lib -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    "${component_flags[@]}"
)
printf '%q ' "$SOURCE_DIR/configure" "${configure_args[@]}" > "$BUILD_ROOT/artifacts/configure-command.sh"
printf '\n' >> "$BUILD_ROOT/artifacts/configure-command.sh"
(
    cd "$BUILD_ROOT/obj"
    "$SOURCE_DIR/configure" "${configure_args[@]}" 2>&1 | tee "$BUILD_ROOT/artifacts/configure-output.txt"
    for feature in GPL NONFREE VERSION3; do
        [[ "$(sed -n "s/^#define CONFIG_${feature} //p" config.h)" == 0 ]] || {
            printf 'Unexpected FFmpeg license configuration: %s\n' "$feature" >&2; exit 1;
        }
    done
    [[ "$(sed -n 's/^#define CONFIG_LIBMP3LAME //p' config.h)" == 1 && \
       "$(sed -n 's/^#define CONFIG_LIBMP3LAME_ENCODER //p' config_components.h)" == 1 && \
       "$(sed -n 's/^#define CONFIG_MP3_MUXER //p' config_components.h)" == 1 ]] || {
        printf 'Real LAME MP3 encoder/muxer was not enabled.\n' >&2; exit 1;
    }
    cp ffbuild/config.log config.h config_components.h ffbuild/config.mak "$BUILD_ROOT/artifacts/"
    make -j "$JOBS" 2>&1 | tee "$BUILD_ROOT/artifacts/build.log"
    make install 2>&1 | tee "$BUILD_ROOT/artifacts/install.log"
)

# Preserve exact input, configuration and licensing with the built libraries.
mkdir -p "$OUTPUT_DIR/build-info" "$OUTPUT_DIR/licenses"
cp "$SCRIPT_DIR/ffmpeg.lock" "$SCRIPT_DIR/lame.lock" "$SCRIPT_DIR/build-ffmpeg.sh" "$OUTPUT_DIR/build-info/"
cp -R "$BUILD_ROOT/artifacts/." "$OUTPUT_DIR/build-info/"
cp "$LAME_PREFIX/lib/libmp3lame.a" "$OUTPUT_DIR/build-info/lame/"
printf 'NDK=%s\nAPI=%s\nABI=arm64-v8a\n' "$NDK_VERSION" "$ANDROID_API" > "$OUTPUT_DIR/build-info/toolchain.txt"
cp "$SOURCE_DIR/COPYING.LGPLv2.1" "$SOURCE_DIR/LICENSE.md" "$OUTPUT_DIR/licenses/"
mkdir -p "$OUTPUT_DIR/licenses/lame"
cp "$LAME_SOURCE_DIR/COPYING" "$LAME_SOURCE_DIR/LICENSE" "$LAME_SOURCE_DIR/README" "$OUTPUT_DIR/licenses/lame/"
for lib in avutil avcodec avformat avfilter swresample; do
    library="$OUTPUT_DIR/lib/lib${lib}.so"
    [[ -f "$library" ]] || { printf 'Missing installed library: %s\n' "$library" >&2; exit 1; }
    "$TOOLCHAIN/bin/llvm-readelf" -lW "$library" >> "$OUTPUT_DIR/build-info/elf-program-headers.txt"
    "$TOOLCHAIN/bin/llvm-readelf" -dW "$library" >> "$OUTPUT_DIR/build-info/elf-dynamic.txt"
    "$TOOLCHAIN/bin/llvm-readelf" -dW "$library" | awk '
        /NEEDED/ && /libmp3lame/ { bad=1 }
        END { exit bad }' || {
        printf 'LAME must be statically linked inside FFmpeg, not a missing shared dependency.\n' >&2; exit 1;
    }
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
printf 'Installed FFmpeg audio libraries (AAC + real LAME MP3): %s\nBuild logs: %s\nSource archives:\n%s\n%s\n' \
    "$OUTPUT_DIR" "$OUTPUT_DIR/build-info" "$ARCHIVE" "$LAME_ARCHIVE"
