#include "engine.hpp"
#include "json.hpp"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libavutil/sha.h>
#include <libavutil/mem.h>
#include <libswresample/swresample.h>
}

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <unistd.h>

namespace audioassistant {
namespace {
constexpr int kRate = 48000;
constexpr int kChannels = 2;
constexpr int kBitRate = 192000;
constexpr int kPeakFrames = 1024;
constexpr AVRational kMicros{1, 1000000};
constexpr AVRational kSamples{1, kRate};

std::string errorText(int error) {
    char text[AV_ERROR_MAX_STRING_SIZE]{};
    av_strerror(error, text, sizeof(text));
    return text;
}
void check(int code, const std::string &operation, Job &job) {
    job.check();
    if (code < 0) throw std::runtime_error(operation + ": " + errorText(code));
}
int interrupted(void *opaque) {
    return static_cast<Job *>(opaque)->cancelled.load(std::memory_order_relaxed) ? 1 : 0;
}
std::string decimal(double value) {
    if (!std::isfinite(value)) throw std::runtime_error("Non-finite audio setting");
    std::ostringstream stream;
    stream.imbue(std::locale::classic());
    stream << std::fixed << std::setprecision(8) << value;
    return stream.str();
}
struct Packet {
    AVPacket *value{av_packet_alloc()};
    Packet() { if (!value) throw std::bad_alloc(); }
    ~Packet() { av_packet_free(&value); }
};
struct Frame {
    AVFrame *value{av_frame_alloc()};
    Frame() { if (!value) throw std::bad_alloc(); }
    ~Frame() { av_frame_free(&value); }
};
struct Input {
    AVFormatContext *format{};
    AVCodecContext *decoder{};
    int streamIndex{-1};
    Job &job;
    Input(const std::string &path, Job &job) : job(job) {
        try {
            job.check();
            format = avformat_alloc_context();
            if (!format) throw std::bad_alloc();
            format->interrupt_callback = {interrupted, &job};
            check(avformat_open_input(&format, path.c_str(), nullptr, nullptr), "Open audio", job);
            check(avformat_find_stream_info(format, nullptr), "Read audio streams", job);
            streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
            check(streamIndex, "Find audio stream", job);
        } catch (...) { avformat_close_input(&format); throw; }
    }
    ~Input() { avcodec_free_context(&decoder); avformat_close_input(&format); }
    AVStream *stream() const { return format->streams[streamIndex]; }
    int64_t durationUs() const {
        if (stream()->duration != AV_NOPTS_VALUE && stream()->duration > 0)
            return av_rescale_q(stream()->duration, stream()->time_base, kMicros);
        return format->duration != AV_NOPTS_VALUE ? std::max<int64_t>(0, format->duration) : 0;
    }
    void openDecoder() {
        const AVCodec *codec = avcodec_find_decoder(stream()->codecpar->codec_id);
        if (!codec) throw std::runtime_error("Audio decoder is not present in FFmpeg build");
        decoder = avcodec_alloc_context3(codec);
        if (!decoder) throw std::bad_alloc();
        check(avcodec_parameters_to_context(decoder, stream()->codecpar), "Configure decoder", job);
        decoder->pkt_timebase = stream()->time_base;
        check(avcodec_open2(decoder, codec, nullptr), "Open audio decoder", job);
    }
    // Decode from an earlier seek point; the caller trims decoded samples by PTS.
    void decode(const std::function<bool(AVFrame *)> &consume, int64_t seekUs = 0) {
        if (!decoder) openDecoder();
        if (seekUs > 0) {
            int64_t start = stream()->start_time == AV_NOPTS_VALUE ? 0 : stream()->start_time;
            int64_t timestamp = av_rescale_q(seekUs, kMicros, stream()->time_base) + start;
            check(av_seek_frame(format, streamIndex, timestamp, AVSEEK_FLAG_BACKWARD), "Seek audio segment", job);
            avcodec_flush_buffers(decoder);
        }
        Packet packet;
        Frame frame;
        bool keepGoing = true;
        auto drain = [&]() {
            while (keepGoing) {
                job.check();
                int result = avcodec_receive_frame(decoder, frame.value);
                if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
                check(result, "Decode audio frame", job);
                keepGoing = consume(frame.value);
                av_frame_unref(frame.value);
            }
        };
        while (keepGoing) {
            job.check();
            int result = av_read_frame(format, packet.value);
            if (result == AVERROR_EOF) break;
            check(result, "Read audio packet", job);
            if (packet.value->stream_index == streamIndex) {
                int sent = avcodec_send_packet(decoder, packet.value);
                if (sent == AVERROR(EAGAIN)) { drain(); sent = avcodec_send_packet(decoder, packet.value); }
                check(sent, "Send audio packet", job);
                drain();
            }
            av_packet_unref(packet.value);
        }
        if (keepGoing) { check(avcodec_send_packet(decoder, nullptr), "Flush audio decoder", job); drain(); }
    }
};
struct File {
    FILE *value{};
    explicit File(const std::string &path, const char *mode) : value(std::fopen(path.c_str(), mode)) {
        if (!value) throw std::runtime_error("Open local audio file: " + std::string(std::strerror(errno)));
    }
    ~File() { if (value) std::fclose(value); }
    void write(const void *data, size_t bytes) {
        if (bytes && std::fwrite(data, 1, bytes, value) != bytes)
            throw std::runtime_error("Write audio failed (check available storage)");
    }
    void flush() { if (std::fflush(value) != 0) throw std::runtime_error("Flush audio file failed"); }
};
struct Scratch {
    std::string directory;
    explicit Scratch(const std::string &cacheDir) {
        std::string pattern = cacheDir + "/audio-job-XXXXXX";
        std::vector<char> buffer(pattern.begin(), pattern.end()); buffer.push_back(0);
        char *created = mkdtemp(buffer.data());
        if (!created) throw std::runtime_error("Create audio cache directory: " + std::string(std::strerror(errno)));
        directory = created;
    }
    ~Scratch() { std::error_code ignored; std::filesystem::remove_all(directory, ignored); }
    std::string path(const std::string &name) const { return directory + "/" + name; }
};
struct RemoveOnFailure {
    std::string path;
    bool complete{};
    ~RemoveOnFailure() { if (!complete) std::remove(path.c_str()); }
};

struct Resampler {
    SwrContext *context{};
    int outRate{};
    int outChannels{};
    Job &job;
    Resampler(AVFrame *input, int rate, const AVChannelLayout &layout, Job &job)
        : outRate(rate), outChannels(layout.nb_channels), job(job) {
        check(swr_alloc_set_opts2(&context, &layout, AV_SAMPLE_FMT_FLT, rate,
                                 &input->ch_layout, static_cast<AVSampleFormat>(input->format), input->sample_rate,
                                 0, nullptr), "Create audio resampler", job);
        if (!context) throw std::bad_alloc();
        try { check(swr_init(context), "Initialize audio resampler", job); }
        catch (...) { swr_free(&context); throw; }
    }
    ~Resampler() { swr_free(&context); }
    std::vector<float> convert(AVFrame *frame, int offset, int samples) {
        job.check();
        std::vector<const uint8_t *> planes;
        if (frame) {
            int channels = frame->ch_layout.nb_channels;
            bool planar = av_sample_fmt_is_planar(static_cast<AVSampleFormat>(frame->format));
            int bytes = av_get_bytes_per_sample(static_cast<AVSampleFormat>(frame->format));
            int count = planar ? channels : 1;
            planes.resize(count);
            for (int ch = 0; ch < count; ++ch)
                planes[ch] = frame->extended_data[ch] + int64_t(offset) * bytes * (planar ? 1 : channels);
        }
        int capacity = std::max(256, swr_get_out_samples(context, samples));
        std::vector<float> result(size_t(capacity) * outChannels);
        uint8_t *output = reinterpret_cast<uint8_t *>(result.data());
        int converted = swr_convert(context, &output, capacity, frame ? planes.data() : nullptr, samples);
        check(converted, "Resample audio", job);
        result.resize(size_t(converted) * outChannels);
        return result;
    }
};
AVChannelLayout stereoLayout() { AVChannelLayout layout = AV_CHANNEL_LAYOUT_STEREO; return layout; }

int64_t frameTimeUs(const Input &input, AVFrame *frame, int64_t fallbackUs) {
    if (frame->best_effort_timestamp == AV_NOPTS_VALUE) return fallbackUs;
    int64_t start = input.stream()->start_time == AV_NOPTS_VALUE ? 0 : input.stream()->start_time;
    return av_rescale_q(frame->best_effort_timestamp - start, input.stream()->time_base, kMicros);
}

// Decode one segment to common-rate float PCM, then append to that song's PCM.
// Trimming occurs in decoded source samples before resampling. Seeking is only
// an acceleration: segment boundaries are never rounded to compressed packets.
int64_t decodeSegment(const std::string &path, const Segment &segment, File &output, Job &job,
                      const std::function<void(double)> &progress) {
    if (segment.startUs < 0 || segment.endUs <= segment.startUs)
        throw std::runtime_error("Audio segment must have a positive duration");
    Input input(path, job);
    int64_t duration = input.durationUs();
    if (duration > 0 && segment.endUs > duration + 100000)
        throw std::runtime_error("Audio segment exceeds the source duration");
    std::unique_ptr<Resampler> resampler;
    auto layout = stereoLayout();
    int64_t framesWritten = 0;
    int64_t expectedFrames = av_rescale_q(segment.endUs - segment.startUs, kMicros, kSamples);
    int64_t fallbackUs = segment.startUs > 2000000 ? segment.startUs - 2000000 : 0;
    bool sawTimestamp = false;
    auto write = [&](std::vector<float> samples) {
        int64_t count = std::min<int64_t>(samples.size() / kChannels, expectedFrames - framesWritten);
        if (count > 0) { output.write(samples.data(), size_t(count) * kChannels * sizeof(float)); framesWritten += count; }
    };
    int64_t seekUs = std::max<int64_t>(0, segment.startUs - 2000000);
    input.decode([&](AVFrame *frame) {
        job.check();
        if (frame->sample_rate <= 0) throw std::runtime_error("Invalid decoded sample rate");
        if (seekUs > 0 && frame->best_effort_timestamp == AV_NOPTS_VALUE && !sawTimestamp)
            throw std::runtime_error("This source has no seek timestamps; convert it to MP3 before trimming");
        sawTimestamp |= frame->best_effort_timestamp != AV_NOPTS_VALUE;
        int64_t beginUs = frameTimeUs(input, frame, fallbackUs);
        fallbackUs = beginUs + av_rescale_q(frame->nb_samples, {1, frame->sample_rate}, kMicros);
        int64_t startSample = av_rescale_rnd(segment.startUs - beginUs, frame->sample_rate, 1000000, AV_ROUND_UP);
        int64_t endSample = av_rescale_rnd(segment.endUs - beginUs, frame->sample_rate, 1000000, AV_ROUND_UP);
        int start = int(std::clamp<int64_t>(startSample, 0, frame->nb_samples));
        int end = int(std::clamp<int64_t>(endSample, 0, frame->nb_samples));
        if (end > start) {
            if (!resampler) resampler = std::make_unique<Resampler>(frame, kRate, layout, job);
            write(resampler->convert(frame, start, end - start));
        }
        progress(std::clamp(double(fallbackUs - segment.startUs) / double(segment.endUs - segment.startUs), 0.0, 1.0));
        return fallbackUs < segment.endUs;
    }, seekUs);
    if (resampler) {
        while (true) { auto tail = resampler->convert(nullptr, 0, 0); if (tail.empty()) break; write(std::move(tail)); }
    }
    if (framesWritten == 0) throw std::runtime_error("The selected segment contains no decoded audio");
    AVCodecID codec = input.stream()->codecpar->codec_id;
    bool paddedCodec = codec == AV_CODEC_ID_MP3 || codec == AV_CODEC_ID_AAC
        || codec == AV_CODEC_ID_OPUS || codec == AV_CODEC_ID_VORBIS;
    bool atReportedEnd = duration > 0 && std::abs(segment.endUs - duration) <= 1000;
    // Some compressed container durations include encoder delay/end padding.
    // Accept a small missing tail only at reported EOF; never synthesize silence.
    int64_t missingTolerance = paddedCodec && atReportedEnd ? kRate / 10 : kRate / 50;
    if (expectedFrames - framesWritten > missingTolerance)
        throw std::runtime_error("Source ended before the selected segment; refresh its duration");
    progress(1.0);
    return framesWritten;
}

// Loudnorm exposes its measured EBU R128 statistics through its documented log
// output on filter destruction. This application serializes all libav jobs;
// thread-local capture cannot intercept another engine job's analysis.
thread_local std::string *analysisLog = nullptr;
void audioLog(void *context, int level, const char *format, va_list args) {
    if (analysisLog && level <= AV_LOG_INFO) {
        char line[4096]{};
        va_list copy; va_copy(copy, args);
        std::vsnprintf(line, sizeof(line), format, copy);
        va_end(copy);
        analysisLog->append(line);
    } else if (level <= AV_LOG_ERROR) {
        av_log_default_callback(context, level, format, args);
    }
}
void initializeLogging() {
    static std::once_flag flag;
    std::call_once(flag, [] { av_log_set_callback(audioLog); av_log_set_level(AV_LOG_INFO); });
}
struct Filter {
    AVFilterGraph *graph{avfilter_graph_alloc()};
    AVFilterContext *source{};
    AVFilterContext *sink{};
    Job &job;
    Filter(const std::string &description, Job &job) : job(job) {
        if (!graph) throw std::bad_alloc();
        AVFilterInOut *inputs = nullptr;
        AVFilterInOut *outputs = nullptr;
        try {
            graph->nb_threads = 1;
            check(avfilter_graph_create_filter(&source, avfilter_get_by_name("abuffer"), "source",
                       "time_base=1/48000:sample_rate=48000:sample_fmt=flt:channel_layout=stereo", nullptr, graph), "Create filter input", job);
            check(avfilter_graph_create_filter(&sink, avfilter_get_by_name("abuffersink"), "sink", nullptr, nullptr, graph), "Create filter output", job);
            outputs = avfilter_inout_alloc(); inputs = avfilter_inout_alloc();
            if (!inputs || !outputs) throw std::bad_alloc();
            outputs->name = av_strdup("in"); outputs->filter_ctx = source; outputs->pad_idx = 0;
            inputs->name = av_strdup("out"); inputs->filter_ctx = sink; inputs->pad_idx = 0;
            check(avfilter_graph_parse_ptr(graph, description.c_str(), &inputs, &outputs, nullptr), "Parse audio filters", job);
            check(avfilter_graph_config(graph, nullptr), "Configure audio filters", job);
            avfilter_inout_free(&inputs); avfilter_inout_free(&outputs);
        } catch (...) { avfilter_inout_free(&inputs); avfilter_inout_free(&outputs); avfilter_graph_free(&graph); throw; }
    }
    ~Filter() { avfilter_graph_free(&graph); }
};
int64_t pcmFrames(const std::string &path) {
    auto size = std::filesystem::file_size(path);
    if (size == 0 || size % (kChannels * sizeof(float))) throw std::runtime_error("Invalid intermediate PCM data");
    return int64_t(size / (kChannels * sizeof(float)));
}
void runFilter(const std::string &inputPath, const std::string &outputPath, const std::string &description,
               Job &job, const std::function<void(double)> &progress) {
    Filter filter(description + ",aresample=48000,aformat=sample_fmts=flt:sample_rates=48000:channel_layouts=stereo", job);
    File input(inputPath, "rb");
    std::unique_ptr<File> output;
    if (!outputPath.empty()) output = std::make_unique<File>(outputPath, "wb");
    int64_t total = pcmFrames(inputPath);
    int64_t readFrames = 0;
    Frame decoded;
    auto drain = [&](bool flushing) {
        while (true) {
            job.check();
            int result = av_buffersink_get_frame(filter.sink, decoded.value);
            if (result == AVERROR_EOF) return;
            if (result == AVERROR(EAGAIN)) {
                if (flushing) throw std::runtime_error("Audio filter did not finish flushing");
                return;
            }
            check(result, "Read filtered audio", job);
            if (decoded.value->format != AV_SAMPLE_FMT_FLT || decoded.value->ch_layout.nb_channels != kChannels)
                throw std::runtime_error("Unexpected normalized PCM format");
            if (output) output->write(decoded.value->data[0], size_t(decoded.value->nb_samples) * kChannels * sizeof(float));
            av_frame_unref(decoded.value);
        }
    };
    std::vector<float> samples(4096 * kChannels);
    while (true) {
        job.check();
        size_t count = std::fread(samples.data(), kChannels * sizeof(float), 4096, input.value);
        if (!count) { if (std::ferror(input.value)) throw std::runtime_error("Read intermediate PCM failed"); break; }
        Frame frame;
        frame.value->format = AV_SAMPLE_FMT_FLT;
        frame.value->sample_rate = kRate;
        frame.value->ch_layout = stereoLayout();
        frame.value->nb_samples = int(count);
        frame.value->pts = readFrames;
        check(av_frame_get_buffer(frame.value, 0), "Allocate filter frame", job);
        std::memcpy(frame.value->data[0], samples.data(), count * kChannels * sizeof(float));
        check(av_buffersrc_add_frame_flags(filter.source, frame.value, AV_BUFFERSRC_FLAG_KEEP_REF), "Feed audio filter", job);
        readFrames += count;
        drain(false);
        progress(double(readFrames) / double(total));
    }
    check(av_buffersrc_add_frame_flags(filter.source, nullptr, 0), "Flush audio filter", job);
    drain(true);
    if (output) output->flush();
    progress(1.0);
}
struct Loudness { double integrated{}; double truePeak{}; double range{}; double threshold{}; double offset{}; bool finite{}; };
Loudness analyze(const std::string &path, const std::string &options, Job &job,
                 const std::function<void(double)> &progress) {
    initializeLogging();
    std::string log;
    struct Capture { explicit Capture(std::string &value) { analysisLog = &value; } ~Capture() { analysisLog = nullptr; } } capture(log);
    runFilter(path, "", options + ":print_format=json", job, progress);
    auto begin = log.rfind('{'); auto end = log.rfind('}');
    if (begin == std::string::npos || end == std::string::npos || begin >= end)
        throw std::runtime_error("FFmpeg loudnorm returned no measured statistics");
    Json value = Json::parse(log.substr(begin, end - begin + 1));
    Loudness result;
    result.integrated = std::stod(value.at("input_i").str());
    result.truePeak = std::stod(value.at("input_tp").str());
    result.range = std::stod(value.at("input_lra").str());
    result.threshold = std::stod(value.at("input_thresh").str());
    result.offset = std::stod(value.at("target_offset").str());
    result.finite = std::isfinite(result.integrated) && std::isfinite(result.truePeak) && std::isfinite(result.range)
        && std::isfinite(result.threshold) && std::isfinite(result.offset);
    return result;
}

// Cache the actual first pass by exact concatenated PCM content and effective
// loudnorm targets. Segment order, source content, and positive-gain peak budget
// therefore invalidate the cache without relying on a filename or modification time.
std::string loudnessCacheKey(const std::string &path, const std::string &options, Job &job) {
    AVSHA *sha = av_sha_alloc();
    if (!sha) throw std::bad_alloc();
    struct Release { AVSHA *value; ~Release() { av_free(value); } } release{sha};
    if (av_sha_init(sha, 256) < 0) throw std::runtime_error("Initialize loudness cache hash failed");
    const std::string version = "audioassistant-loudnorm-ffmpeg7-v1\n" + options + "\n";
    av_sha_update(sha, reinterpret_cast<const uint8_t *>(version.data()), version.size());
    File input(path, "rb");
    std::array<uint8_t, 65536> buffer{};
    while (true) {
        job.check();
        size_t count = std::fread(buffer.data(), 1, buffer.size(), input.value);
        if (count) av_sha_update(sha, buffer.data(), count);
        if (count < buffer.size()) {
            if (std::ferror(input.value)) throw std::runtime_error("Hash loudness input failed");
            break;
        }
    }
    uint8_t digest[32];
    av_sha_final(sha, digest);
    std::ostringstream key;
    for (uint8_t byte : digest) key << std::hex << std::setw(2) << std::setfill('0') << unsigned(byte);
    return key.str();
}
Loudness analyzeCached(const std::string &path, const std::string &options, const std::string &cacheDir,
                       Job &job, const std::function<void(double)> &progress) {
    std::string directory = cacheDir + "/loudness-v1";
    std::filesystem::create_directories(directory);
    std::string cache = directory + "/" + loudnessCacheKey(path, options, job) + ".json";
    try {
        std::ifstream input(cache);
        std::string text;
        if (input) {
            text.resize(4097);
            input.read(text.data(), text.size());
            text.resize(size_t(input.gcount()));
            if (text.size() > 4096) throw std::runtime_error("Oversized loudness cache");
            auto json = Json::parse(text);
            if (json.at("version").num() != 1) throw std::runtime_error("Old loudness cache");
            Loudness result;
            result.finite = json.at("finite").flag();
            if (result.finite) {
                result.integrated = json.at("inputI").num();
                result.truePeak = json.at("inputTp").num();
                result.range = json.at("inputLra").num();
                result.threshold = json.at("inputThresh").num();
                result.offset = json.at("targetOffset").num();
                if (result.integrated < -200 || result.integrated > 100 || result.truePeak < -200 || result.truePeak > 100
                    || result.range < 0 || result.range > 100 || result.threshold < -200 || result.threshold > 100
                    || std::abs(result.offset) > 200) throw std::runtime_error("Invalid loudness cache");
            }
            progress(1);
            return result;
        }
    } catch (const Cancelled &) { throw; }
    catch (const std::exception &) { std::remove(cache.c_str()); }
    Loudness result = analyze(path, options, job, progress);
    std::string text = "{\"version\":1,\"finite\":" + std::string(result.finite ? "true" : "false");
    if (result.finite) {
        text += ",\"inputI\":" + decimal(result.integrated) + ",\"inputTp\":" + decimal(result.truePeak)
            + ",\"inputLra\":" + decimal(result.range) + ",\"inputThresh\":" + decimal(result.threshold)
            + ",\"targetOffset\":" + decimal(result.offset);
    }
    text += '}';
    RemoveOnFailure partial{cache + ".partial"};
    {
        File output(partial.path, "wb");
        output.write(text.data(), text.size());
        output.flush();
    }
    job.check();
    if (std::rename(partial.path.c_str(), cache.c_str()) != 0)
        throw std::runtime_error("Save measured loudness cache failed");
    partial.complete = true;
    return result;
}

struct AacWriter {
    AVFormatContext *format{};
    AVCodecContext *encoder{};
    AVStream *stream{};
    int64_t pts{};
    Job &job;
    explicit AacWriter(const std::string &path, Job &job) : job(job) {
        try {
            check(avformat_alloc_output_context2(&format, nullptr, "ipod", path.c_str()), "Create M4A output", job);
            if (!format) throw std::bad_alloc();
            format->interrupt_callback = {interrupted, &job};
            const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
            if (!codec) throw std::runtime_error("FFmpeg AAC encoder unavailable");
            encoder = avcodec_alloc_context3(codec);
            if (!encoder) throw std::bad_alloc();
            encoder->sample_rate = kRate;
            encoder->ch_layout = stereoLayout();
            encoder->sample_fmt = AV_SAMPLE_FMT_FLTP;
            encoder->bit_rate = kBitRate;
            encoder->time_base = kSamples;
            if (format->oformat->flags & AVFMT_GLOBALHEADER) encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
            check(avcodec_open2(encoder, codec, nullptr), "Open AAC encoder", job);
            stream = avformat_new_stream(format, nullptr);
            if (!stream) throw std::bad_alloc();
            stream->time_base = encoder->time_base;
            check(avcodec_parameters_from_context(stream->codecpar, encoder), "Configure M4A track", job);
            if (!(format->oformat->flags & AVFMT_NOFILE)) check(avio_open2(&format->pb, path.c_str(), AVIO_FLAG_WRITE, &format->interrupt_callback, nullptr), "Open M4A output", job);
            AVDictionary *options = nullptr;
            av_dict_set(&options, "movflags", "+faststart", 0);
            int result = avformat_write_header(format, &options);
            av_dict_free(&options);
            check(result, "Write M4A header", job);
        } catch (...) { cleanup(); throw; }
    }
    ~AacWriter() { cleanup(); }
    void cleanup() {
        avcodec_free_context(&encoder);
        if (format) { if (!(format->oformat->flags & AVFMT_NOFILE)) avio_closep(&format->pb); avformat_free_context(format); format = nullptr; }
    }
    int frameSize() const { return encoder->frame_size > 0 ? encoder->frame_size : 1024; }
    void drain() {
        Packet packet;
        while (true) {
            job.check();
            int result = avcodec_receive_packet(encoder, packet.value);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
            check(result, "Receive AAC packet", job);
            av_packet_rescale_ts(packet.value, encoder->time_base, stream->time_base);
            packet.value->stream_index = stream->index;
            check(av_interleaved_write_frame(format, packet.value), "Write AAC packet", job);
            av_packet_unref(packet.value);
        }
    }
    void push(const float *interleaved, int count) {
        Frame frame;
        frame.value->format = encoder->sample_fmt;
        frame.value->sample_rate = kRate;
        check(av_channel_layout_copy(&frame.value->ch_layout, &encoder->ch_layout), "Set AAC channel layout", job);
        frame.value->nb_samples = count;
        frame.value->pts = pts;
        check(av_frame_get_buffer(frame.value, 0), "Allocate AAC frame", job);
        for (int ch = 0; ch < kChannels; ++ch) {
            float *plane = reinterpret_cast<float *>(frame.value->data[ch]);
            for (int i = 0; i < count; ++i) plane[i] = interleaved[size_t(i) * kChannels + ch];
        }
        check(avcodec_send_frame(encoder, frame.value), "Encode AAC frame", job);
        pts += count;
        drain();
    }
    void finish() {
        check(avcodec_send_frame(encoder, nullptr), "Flush AAC encoder", job);
        drain();
        check(av_write_trailer(format), "Finalize M4A", job);
    }
};
// The conversion target is MP3; engineering project export remains AAC/M4A.
// Query LAME's supported PCM formats instead of assuming the AAC planar format.
struct Mp3Writer {
    AVFormatContext *format{};
    AVCodecContext *encoder{};
    AVStream *stream{};
    int64_t pts{};
    Job &job;
    explicit Mp3Writer(const std::string &path, Job &job) : job(job) {
        try {
            const AVCodec *codec = avcodec_find_encoder_by_name("libmp3lame");
            if (!codec) throw std::runtime_error("libmp3lame encoder unavailable; rebuild the native FFmpeg dependencies with LAME");
            check(avformat_alloc_output_context2(&format, nullptr, "mp3", path.c_str()), "Create MP3 output", job);
            if (!format) throw std::bad_alloc();
            format->interrupt_callback = {interrupted, &job};
            encoder = avcodec_alloc_context3(codec);
            if (!encoder) throw std::bad_alloc();
            encoder->sample_rate = kRate;
            encoder->ch_layout = stereoLayout();
            encoder->bit_rate = kBitRate;
            encoder->time_base = kSamples;
            const void *configuration = nullptr;
            int count = 0;
            check(avcodec_get_supported_config(encoder, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0,
                                               &configuration, &count), "Query MP3 sample formats", job);
            const auto *formats = static_cast<const AVSampleFormat *>(configuration);
            auto supports = [&](AVSampleFormat candidate) {
                if (!formats) return true;
                return std::find(formats, formats + count, candidate) != formats + count;
            };
            if (supports(AV_SAMPLE_FMT_FLTP)) encoder->sample_fmt = AV_SAMPLE_FMT_FLTP;
            else if (supports(AV_SAMPLE_FMT_S16P)) encoder->sample_fmt = AV_SAMPLE_FMT_S16P;
            else throw std::runtime_error("libmp3lame exposes no supported float or signed-16 planar format");
            check(avcodec_open2(encoder, codec, nullptr), "Open libmp3lame encoder", job);
            stream = avformat_new_stream(format, nullptr);
            if (!stream) throw std::bad_alloc();
            stream->time_base = encoder->time_base;
            // This copies encoder initial_padding. Packet skip-sample side data
            // is retained below so the MP3 muxer can write LAME delay/padding.
            check(avcodec_parameters_from_context(stream->codecpar, encoder), "Configure MP3 track", job);
            check(avio_open2(&format->pb, path.c_str(), AVIO_FLAG_WRITE,
                             &format->interrupt_callback, nullptr), "Open MP3 output", job);
            AVDictionary *options = nullptr;
            av_dict_set(&options, "write_xing", "1", 0);
            av_dict_set(&options, "id3v2_version", "3", 0);
            int result = avformat_write_header(format, &options);
            av_dict_free(&options);
            check(result, "Write MP3 header", job);
        } catch (...) { cleanup(); throw; }
    }
    ~Mp3Writer() { cleanup(); }
    void cleanup() {
        avcodec_free_context(&encoder);
        if (format) { avio_closep(&format->pb); avformat_free_context(format); format = nullptr; }
    }
    int frameSize() const { return encoder->frame_size > 0 ? encoder->frame_size : 1152; }
    void drain() {
        Packet packet;
        while (true) {
            job.check();
            int result = avcodec_receive_packet(encoder, packet.value);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
            check(result, "Receive MP3 packet", job);
            av_packet_rescale_ts(packet.value, encoder->time_base, stream->time_base);
            packet.value->stream_index = stream->index;
            check(av_interleaved_write_frame(format, packet.value), "Write MP3 packet", job);
            av_packet_unref(packet.value);
        }
    }
    void push(const float *interleaved, int count) {
        Frame frame;
        frame.value->format = encoder->sample_fmt;
        frame.value->sample_rate = encoder->sample_rate;
        check(av_channel_layout_copy(&frame.value->ch_layout, &encoder->ch_layout), "Set MP3 channel layout", job);
        frame.value->nb_samples = count;
        frame.value->pts = pts;
        check(av_frame_get_buffer(frame.value, 0), "Allocate MP3 frame", job);
        for (int ch = 0; ch < kChannels; ++ch) {
            for (int i = 0; i < count; ++i) {
                float value = interleaved[size_t(i) * kChannels + ch];
                if (!std::isfinite(value)) throw std::runtime_error("Non-finite decoded audio sample");
                if (encoder->sample_fmt == AV_SAMPLE_FMT_FLTP)
                    reinterpret_cast<float *>(frame.value->data[ch])[i] = value;
                else {
                    long quantized = std::lround(std::clamp(double(value), -1.0, 1.0) * 32768.0);
                    reinterpret_cast<int16_t *>(frame.value->data[ch])[i] = int16_t(std::clamp<long>(quantized, -32768, 32767));
                }
            }
        }
        // libmp3lame advertises SMALL_LAST_FRAME: send the true final sample
        // count and flush, allowing the encoder to report its actual padding.
        check(avcodec_send_frame(encoder, frame.value), "Encode MP3 frame", job);
        pts += count;
        drain();
    }
    void finish() {
        check(avcodec_send_frame(encoder, nullptr), "Flush libmp3lame encoder", job);
        drain();
        // Seekable local output lets the muxer patch Xing length and LAME
        // delay/end-padding fields; merely closing the file would lose these.
        check(av_write_trailer(format), "Finalize MP3", job);
    }
};

template<typename Writer>
void encodePcmWith(const std::vector<std::string> &paths, const std::string &outputPath, Job &job,
                   const std::function<void(double)> &progress) {
    Writer writer(outputPath, job);
    int64_t total = 0;
    for (const auto &path : paths) total += pcmFrames(path);
    int64_t sent = 0;
    int buffered = 0;
    std::vector<float> pending(size_t(writer.frameSize()) * kChannels);
    for (const auto &path : paths) {
        File input(path, "rb");
        while (true) {
            job.check();
            size_t read = std::fread(pending.data() + buffered * kChannels, sizeof(float) * kChannels,
                                     writer.frameSize() - buffered, input.value);
            buffered += int(read);
            if (buffered == writer.frameSize()) { writer.push(pending.data(), buffered); sent += buffered; buffered = 0; progress(double(sent) / double(total)); }
            if (!read) { if (std::ferror(input.value)) throw std::runtime_error("Read final audio data failed"); break; }
        }
    }
    if (buffered) writer.push(pending.data(), buffered);
    writer.finish();
    progress(1.0);
}
void encodePcm(const std::vector<std::string> &paths, const std::string &outputPath, Job &job,
               const std::function<void(double)> &progress) {
    encodePcmWith<AacWriter>(paths, outputPath, job, progress);
}
void encodeMp3Pcm(const std::vector<std::string> &paths, const std::string &outputPath, Job &job,
                  const std::function<void(double)> &progress) {
    encodePcmWith<Mp3Writer>(paths, outputPath, job, progress);
}
void remuxMp3(Input &input, const std::string &outputPath, Job &job) {
    AVFormatContext *output = nullptr;
    auto cleanup = [&]() { if (output) { if (!(output->oformat->flags & AVFMT_NOFILE)) avio_closep(&output->pb); avformat_free_context(output); output = nullptr; } };
    try {
        check(avformat_alloc_output_context2(&output, nullptr, "mp3", outputPath.c_str()), "Create extracted MP3", job);
        if (!output) throw std::bad_alloc();
        output->interrupt_callback = {interrupted, &job};
        AVStream *stream = avformat_new_stream(output, nullptr);
        if (!stream) throw std::bad_alloc();
        check(avcodec_parameters_copy(stream->codecpar, input.stream()->codecpar), "Copy compatible audio stream", job);
        stream->codecpar->codec_tag = 0;
        stream->time_base = input.stream()->time_base;
        check(avio_open2(&output->pb, outputPath.c_str(), AVIO_FLAG_WRITE, &output->interrupt_callback, nullptr), "Open extracted MP3", job);
        AVDictionary *options = nullptr;
        av_dict_set(&options, "write_xing", "1", 0);
        av_dict_set(&options, "id3v2_version", "3", 0);
        int headerResult = avformat_write_header(output, &options);
        av_dict_free(&options);
        check(headerResult, "Write extracted MP3 header", job);
        Packet packet;
        int64_t offset = input.stream()->start_time;
        int64_t duration = input.durationUs();
        int64_t packetCount = 0;
        while (true) {
            job.check();
            int result = av_read_frame(input.format, packet.value);
            if (result == AVERROR_EOF) break;
            check(result, "Read source audio packet", job);
            if (packet.value->stream_index == input.streamIndex) {
                if (offset == AV_NOPTS_VALUE) offset = packet.value->dts != AV_NOPTS_VALUE ? packet.value->dts : packet.value->pts;
                int64_t current = packet.value->pts;
                if (offset != AV_NOPTS_VALUE) {
                    if (packet.value->pts != AV_NOPTS_VALUE) packet.value->pts -= offset;
                    if (packet.value->dts != AV_NOPTS_VALUE) packet.value->dts -= offset;
                }
                av_packet_rescale_ts(packet.value, input.stream()->time_base, stream->time_base);
                packet.value->stream_index = stream->index;
                packet.value->pos = -1;
                check(av_interleaved_write_frame(output, packet.value), "Remux compatible MP3 packet", job);
                ++packetCount;
                if (current != AV_NOPTS_VALUE && duration > 0)
                    job.report(double(av_rescale_q(current - (offset == AV_NOPTS_VALUE ? 0 : offset), input.stream()->time_base, kMicros)) / double(duration));
            }
            av_packet_unref(packet.value);
        }
        if (!packetCount) throw std::runtime_error("No decodable audio found in source");
        check(av_write_trailer(output), "Finalize extracted MP3", job);
        cleanup();
    } catch (...) { cleanup(); throw; }
}
void little32(File &file, uint32_t value) {
    uint8_t bytes[4]; for (int i = 0; i < 4; ++i) bytes[i] = uint8_t(value >> (i * 8)); file.write(bytes, sizeof(bytes));
}
void little64(File &file, uint64_t value) {
    uint8_t bytes[8]; for (int i = 0; i < 8; ++i) bytes[i] = uint8_t(value >> (i * 8)); file.write(bytes, sizeof(bytes));
}
void littleFloat(File &file, float value) { uint32_t bits; static_assert(sizeof(float) == 4); std::memcpy(&bits, &value, 4); little32(file, bits); }
} // namespace

void Job::report(double value) const { check(); if (progress) progress(std::clamp(value, 0.0, 1.0)); }

Metadata probe(const std::string &path, Job &job) {
    Input input(path, job);
    auto parameters = input.stream()->codecpar;
    if (parameters->sample_rate <= 0 || parameters->ch_layout.nb_channels <= 0)
        throw std::runtime_error("Audio stream has no valid sample rate or channels");
    return {input.durationUs(), parameters->sample_rate, parameters->ch_layout.nb_channels,
            avcodec_get_name(parameters->codec_id)};
}

void waveform(const std::string &path, const std::string &outputPath, Job &job) {
    if (path == outputPath) throw std::runtime_error("Waveform output cannot replace its source");
    RemoveOnFailure cleanup{outputPath};
    Input input(path, job);
    File output(outputPath, "wb+");
    std::unique_ptr<Resampler> resampler;
    int channels = 0;
    int sampleRate = 0;
    int windowCount = 0;
    uint64_t totalFrames = 0;
    uint64_t peakCount = 0;
    std::vector<float> peaks;
    auto flushPeak = [&]() {
        if (!windowCount) return;
        for (float value : peaks) littleFloat(output, value);
        ++peakCount; std::fill(peaks.begin(), peaks.end(), 0.0f); windowCount = 0;
    };
    auto consumeSamples = [&](const std::vector<float> &samples) {
        for (size_t i = 0; i < samples.size() / channels; ++i) {
            for (int ch = 0; ch < channels; ++ch) {
                float value = samples[i * channels + ch];
                if (!std::isfinite(value)) throw std::runtime_error("Non-finite decoded audio sample");
                peaks[ch] = std::max(peaks[ch], std::abs(value));
            }
            ++totalFrames;
            if (++windowCount == kPeakFrames) flushPeak();
        }
    };
    input.decode([&](AVFrame *frame) {
        if (!resampler) {
            channels = frame->ch_layout.nb_channels;
            sampleRate = frame->sample_rate;
            if (channels < 1 || channels > 64 || sampleRate < 1) throw std::runtime_error("Unsupported waveform audio format");
            resampler = std::make_unique<Resampler>(frame, sampleRate, frame->ch_layout, job);
            peaks.assign(channels, 0.0f);
            output.write("AWF1", 4); little32(output, 1); little32(output, sampleRate); little32(output, channels);
            little64(output, 0); little32(output, kPeakFrames); little64(output, 0);
        }
        consumeSamples(resampler->convert(frame, 0, frame->nb_samples));
        int64_t duration = input.durationUs();
        if (duration > 0) job.report(std::min(0.99, double(totalFrames) / sampleRate * 1000000.0 / double(duration)));
        return true;
    });
    if (!resampler) throw std::runtime_error("Source contains no decodable audio");
    while (true) { auto tail = resampler->convert(nullptr, 0, 0); if (tail.empty()) break; consumeSamples(tail); }
    flushPeak();
    if (peakCount == 0) throw std::runtime_error("Source contains no waveform samples");
    if (std::fseek(output.value, 16, SEEK_SET)) throw std::runtime_error("Seek waveform header failed");
    little64(output, uint64_t(av_rescale_q(int64_t(totalFrames), {1, sampleRate}, kMicros)));
    if (std::fseek(output.value, 28, SEEK_SET)) throw std::runtime_error("Seek waveform count failed");
    little64(output, peakCount);
    output.flush();
    job.report(1.0);
    cleanup.complete = true;
}

void extract(const std::string &path, const std::string &outputPath, Job &job) {
    if (path == outputPath) throw std::runtime_error("Extracted output cannot replace its source");
    RemoveOnFailure cleanup{outputPath};
    Input input(path, job);
    const auto *parameters = input.stream()->codecpar;
    const AVOutputFormat *mp3 = av_guess_format("mp3", nullptr, nullptr);
    if (!mp3) throw std::runtime_error("FFmpeg MP3 muxer unavailable; rebuild the native FFmpeg dependencies");
    bool canRemux = parameters->codec_id == AV_CODEC_ID_MP3 && parameters->sample_rate > 0
        && parameters->ch_layout.nb_channels >= 1 && parameters->ch_layout.nb_channels <= 2
        && avformat_query_codec(mp3, parameters->codec_id, FF_COMPLIANCE_NORMAL) > 0;
    if (canRemux) {
        // Keep compressed packets and their gapless skip-sample side data.
        remuxMp3(input, outputPath, job);
    } else {
        // Fail before decoding when the required actual MP3 encoder is missing.
        if (!avcodec_find_encoder_by_name("libmp3lame"))
            throw std::runtime_error("libmp3lame encoder unavailable; rebuild the native FFmpeg dependencies with LAME");
        std::string parent = std::filesystem::path(outputPath).parent_path().string();
        if (parent.empty()) parent = ".";
        Scratch scratch(parent);
        std::string pcm = scratch.path("extracted.f32");
        File file(pcm, "wb");
        std::unique_ptr<Resampler> resampler;
        auto layout = stereoLayout();
        int64_t frames = 0;
        input.decode([&](AVFrame *frame) {
            if (!resampler) resampler = std::make_unique<Resampler>(frame, kRate, layout, job);
            auto samples = resampler->convert(frame, 0, frame->nb_samples);
            file.write(samples.data(), samples.size() * sizeof(float));
            frames += samples.size() / kChannels;
            if (input.durationUs() > 0) job.report(std::min(0.65, 0.65 * double(frames) / kRate * 1000000.0 / double(input.durationUs())));
            return true;
        });
        if (!resampler) throw std::runtime_error("No decodable audio found in source");
        while (true) { auto tail = resampler->convert(nullptr, 0, 0); if (tail.empty()) break; file.write(tail.data(), tail.size() * sizeof(float)); }
        file.flush();
        encodeMp3Pcm({pcm}, outputPath, job, [&](double value) { job.report(0.65 + 0.35 * value); });
    }
    job.report(1.0);
    cleanup.complete = true;
}

void exportProject(const Export &project, const std::string &outputPath, const std::string &cacheDir, Job &job) {
    if (project.songs.empty()) throw std::runtime_error("Add at least one song before exporting");
    if (project.sampleRate != kRate || project.bitRate != kBitRate)
        throw std::runtime_error("This engine exports AAC M4A at 48000 Hz / 192000 bit/s");
    if (!std::isfinite(project.targetLufs) || project.targetLufs < -70 || project.targetLufs > -5
        || !std::isfinite(project.truePeakDb) || project.truePeakDb < -9 || project.truePeakDb > 0
        || !std::isfinite(project.lra) || project.lra < 1 || project.lra > 50 || project.fadeMs < 0)
        throw std::runtime_error("Export loudness or fade setting is outside FFmpeg's supported range");
    for (const auto &song : project.songs) {
        if (song.path == outputPath) throw std::runtime_error("Export cannot replace a source file");
        if (song.segments.empty()) throw std::runtime_error("Every song must contain a segment");
        if (!std::isfinite(song.gainDb) || song.gainDb < -60 || song.gainDb > 24)
            throw std::runtime_error("Song gain must be between -60 and +24 dB");
        if (project.normalize && project.truePeakDb - std::max(0.0, song.gainDb) < -9)
            throw std::runtime_error("Positive gain leaves insufficient true-peak headroom; reduce song gain");
    }
    RemoveOnFailure cleanup{outputPath};
    Scratch scratch(cacheDir);
    std::vector<std::string> finished;
    const double songShare = 0.85 / project.songs.size();
    for (size_t songIndex = 0; songIndex < project.songs.size(); ++songIndex) {
        job.check();
        const Song &song = project.songs[songIndex];
        double base = songIndex * songShare;
        std::string raw = scratch.path("song-" + std::to_string(songIndex) + ".f32");
        std::string processed = scratch.path("processed-" + std::to_string(songIndex) + ".f32");
        int64_t durationUs = 0;
        for (const auto &segment : song.segments) {
            if (segment.startUs < 0 || segment.endUs <= segment.startUs || segment.endUs - segment.startUs > std::numeric_limits<int64_t>::max() - durationUs)
                throw std::runtime_error("Invalid song segment duration");
            durationUs += segment.endUs - segment.startUs;
        }
        File output(raw, "wb");
        int64_t completedUs = 0;
        for (const auto &segment : song.segments) {
            decodeSegment(song.path, segment, output, job, [&](double value) {
                double fraction = (double(completedUs) + value * double(segment.endUs - segment.startUs)) / double(durationUs);
                job.report(base + songShare * 0.40 * fraction);
            });
            completedUs += segment.endUs - segment.startUs;
        }
        output.flush();
        std::string chain;
        if (project.normalize) {
            double peakBudget = project.truePeakDb - std::max(0.0, song.gainDb);
            std::string options = "loudnorm=I=" + decimal(project.targetLufs) + ":TP=" + decimal(peakBudget) + ":LRA=" + decimal(project.lra);
            auto measured = analyzeCached(raw, options, cacheDir, job, [&](double value) { job.report(base + songShare * (0.40 + 0.25 * value)); });
            chain = options;
            if (measured.finite) {
                chain += ":measured_I=" + decimal(measured.integrated) + ":measured_TP=" + decimal(measured.truePeak)
                    + ":measured_LRA=" + decimal(measured.range) + ":measured_thresh=" + decimal(measured.threshold)
                    + ":offset=" + decimal(measured.offset) + ":linear=true";
            } else {
                // Silence has -inf loudness. A second dynamic pass handles this
                // correctly; passing non-finite measured values is invalid.
                chain += ":linear=false";
            }
            chain += ":print_format=none,";
        }
        chain += "volume=" + decimal(song.gainDb) + "dB";
        int64_t total = pcmFrames(raw);
        double duration = double(total) / kRate;
        double fade = std::min(double(project.fadeMs) / 1000.0, duration / 2.0);
        if (fade > 0) chain += ",afade=t=in:d=" + decimal(fade) + ",afade=t=out:st=" + decimal(duration - fade) + ":d=" + decimal(fade);
        double secondBase = project.normalize ? 0.65 : 0.40;
        runFilter(raw, processed, chain, job, [&](double value) {
            job.report(base + songShare * (secondBase + (1.0 - secondBase) * value));
        });
        finished.push_back(processed);
        std::remove(raw.c_str());
    }
    encodePcm(finished, outputPath, job, [&](double value) { job.report(0.85 + 0.15 * value); });
    job.report(1.0);
    cleanup.complete = true;
}
} // namespace audioassistant
