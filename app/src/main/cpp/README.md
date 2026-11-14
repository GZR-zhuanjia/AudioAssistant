# Structured libav engine

`engine.cpp` calls FFmpeg 7.1.1 libavformat/libavcodec/libavfilter/libswresample
APIs directly. It never starts a process or invokes `ffmpeg_main`. Install the
shared Android libraries with `scripts/build-ffmpeg.sh`; CMake checks their
presence. The app loads the native bridge on the first operation.

`NativeEngine` offers suspending probe/waveform/extract/export operations on
`Dispatchers.IO`. A process-wide Kotlin mutex serializes calls. The JNI bridge
also refuses concurrent jobs. Cancellation sets an atomic flag that decoder,
resampler, filter, encoder, file-processing loops and libav interrupt callbacks
observe. Cancelling the coroutine invokes the same flag. Closing the engine
removes its handle immediately; an active job retains shared ownership until
all libav contexts and scratch files have been released. Progress callbacks run
on the IO worker and report a nondecreasing fraction in [0, 1].

## Export

Each half-open source interval is sought to an earlier compressed packet,
decoded, trimmed in decoded source samples, and resampled to 48 kHz stereo
float PCM. Trimming therefore does not round cuts to AAC/MP3 packet boundaries.
The retained intervals are appended into one PCM file per song before any
loudness analysis. Files that cannot supply seek timestamps fail clearly when
accurate seeking is required. Compressed container durations can include encoder
padding; up to 100 ms of absent padding is accepted only at reported source EOF.
The engine writes actual decoded samples and never synthesizes a replacement tail.

When normalization is enabled, the first `loudnorm` pass measures integrated
loudness, true peak, loudness range, threshold, and target offset. The second
pass supplies those measured values and uses `linear=true` (FFmpeg falls back
to its dynamic mode when the measured constraints do not allow linear gain).
Silence produces non-finite EBU measurements and uses a second dynamic pass
without invalid measured arguments. Both are real filter passes.

First-pass statistics are cached under `cacheDir/loudness-v1/`. The key is a
SHA-256 of the exact concatenated PCM bytes, engine cache version, and effective
loudnorm target options. It therefore changes when retained content, order,
source audio, or effective normalization targets change. Validated JSON is
written through a `.partial` file and renamed atomically. Invalid cache entries
are discarded and measured again; silence measurements are cached as an
explicit non-finite result. Export scratch directories and partial output are
removed on failure or cancellation; completed analysis caches remain reusable.

Manual gain follows normalization. Positive gain reserves equivalent headroom
in the loudnorm true-peak target: effective TP = configured TP - max(0, gainDb).
FFmpeg supports TP targets from -9 to 0 dBTP; larger headroom requirements fail
with a request to lower gain. A loudness target can be constrained by the peak
limit. This target applies to PCM before AAC encoding; lossy AAC can overshoot
on decoding, so it is not a guarantee of a decoded-file true-peak ceiling.
Without normalization, gain is unrestricted by the true-peak setting and can
clip. Optional fades run at the outer edges of each edited song. The processed
song PCM files are streamed consecutively into one AAC encoder so there is no
compressed-packet concatenation or repeated AAC priming between songs. Output
is stereo AAC in M4A, 48 kHz, 192 kbit/s.

## Video conversion to MP3

`NativeEngine.extract(input, output, onProgress)` keeps only the best audio
stream and writes a real MP3 container using the explicit `mp3` muxer,
independent of the temporary output filename extension. Compatible MP3 audio
is copied as compressed packets, preserving its existing rate, channels,
bitrate and gapless skip-sample side data. This path avoids another lossy
encoding pass; metadata and the MP3 container header may be rewritten.

Every other source codec, including AAC or ALAC inside an existing M4A, is
decoded and resampled to 48 kHz stereo float PCM, then encoded with the actual
`libmp3lame` encoder at 192 kbit/s. The encoder's supported formats are queried:
planar float is preferred, with signed-16 planar conversion when required.
Final partial PCM frames are sent at their true length. Encoder initial delay
and packet skip/end-padding metadata are kept, the encoder is flushed, and
the seekable output trailer updates Xing/LAME duration and padding fields.
Players that ignore gapless metadata may still expose encoder delay/padding;
container duration can include it. Waveform duration comes from decoded samples.

MP3 conversion requires the `mp3` muxer and, for transcoding, FFmpeg built with
LGPL LAME and `libmp3lame` enabled. A missing encoder produces an actionable
error before decoding; there is no alternate codec written under an `.mp3`
extension. Scratch PCM and incomplete outputs are removed on cancellation or
failure. Existing M4A/AAC/ALAC sources remain readable. Engineering project
export continues to use AAC in M4A at 48 kHz / 192 kbit/s.

## AWF1 waveform file

All integers and float32 values are little-endian. The fixed header is 36 bytes:

| Offset | Type | Value |
| --- | --- | --- |
| 0 | char[4] | `AWF1` |
| 4 | uint32 | version `1` |
| 8 | uint32 | decoded sample rate |
| 12 | uint32 | channel count, 1 through 64 |
| 16 | uint64 | actual decoded duration in microseconds |
| 24 | uint32 | decoded frames per peak, `1024` |
| 28 | uint64 | number of peak windows |

The remaining bytes contain `peakCount * channels` float32 absolute peak
values, interleaved by window then channel. A partial final window is included.
Each channel retains its own peak. Float audio may have values above 1.0; readers
may clamp when drawing. A successfully generated file always has at least one
peak window. The duration comes from the actual decoded frame count, avoiding
inaccurate container metadata. Header duration/count placeholders are patched
only after decoding completes.

## Host verification

`scripts/test-native-host.sh` links the same engine source to an already built
official FFmpeg 7.1.1 Linux static library tree. Set `FFMPEG_HOST_SOURCE` to that
tree. This is an integration test of real decoding, resampling, filtering,
encoding, remuxing and cancellation; it does not build Android artifacts.
Android ABI, packaging and device execution still require separate verification.
