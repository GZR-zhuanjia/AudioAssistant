#!/usr/bin/env bash
set -euo pipefail
# Uses an already configured/built Linux host FFmpeg 7.1.1 tree. No NDK/Gradle/APK.
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
FFMPEG_HOST_SOURCE="${FFMPEG_HOST_SOURCE:?Set FFMPEG_HOST_SOURCE to built official FFmpeg 7.1.1 source tree}"
TEST_WORK="${TEST_WORK:-$(mktemp -d)}"
mkdir -p "$TEST_WORK"
python3 - "$TEST_WORK" <<'PY'
import math, pathlib, struct, sys, wave
root = pathlib.Path(sys.argv[1])
for name, rate, channels, duration, amplitude in [
    ('mono', 44100, 1, 6, .2), ('stereo', 48000, 2, 6, .04), ('silence', 48000, 2, 4, 0)
]:
    with wave.open(str(root / (name + '.wav')), 'wb') as f:
        f.setparams((channels, 2, rate, 0, 'NONE', 'not compressed'))
        for i in range(rate * duration):
            sample = int(32767 * amplitude * math.sin(2 * math.pi * 440 * i / rate))
            f.writeframesraw(struct.pack('<h', sample) * channels)
(root / 'broken.wav').write_bytes(b'corrupt audio')
PY
if command -v ffmpeg >/dev/null; then
    ffmpeg -v error -y -i "$TEST_WORK/mono.wav" -c:a alac "$TEST_WORK/alac.m4a"
fi
g++ -std=c++17 -O1 -Wall -Wextra -Wpedantic \
    -I"$FFMPEG_HOST_SOURCE" -I"$ROOT/app/src/main/cpp" \
    "$ROOT/scripts/native-smoke.cpp" "$ROOT/app/src/main/cpp/engine.cpp" \
    -Wl,--start-group \
    "$FFMPEG_HOST_SOURCE/libavfilter/libavfilter.a" \
    "$FFMPEG_HOST_SOURCE/libavformat/libavformat.a" \
    "$FFMPEG_HOST_SOURCE/libavcodec/libavcodec.a" \
    "$FFMPEG_HOST_SOURCE/libswresample/libswresample.a" \
    "$FFMPEG_HOST_SOURCE/libavutil/libavutil.a" \
    -Wl,--end-group -lm -lpthread -o "$TEST_WORK/native-smoke"
"$TEST_WORK/native-smoke" "$TEST_WORK"
printf 'Host test artifacts: %s\n' "$TEST_WORK"
