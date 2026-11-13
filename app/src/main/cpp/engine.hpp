#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

namespace audioassistant {

struct Cancelled : std::runtime_error { Cancelled() : std::runtime_error("Audio operation cancelled") {} };
struct Job {
    std::atomic<bool> cancelled{false};
    std::function<void(double)> progress;
    void check() const { if (cancelled.load(std::memory_order_relaxed)) throw Cancelled(); }
    void report(double value) const;
};
struct Metadata { int64_t durationUs{}; int sampleRate{}; int channels{}; std::string codec; };
struct Segment { int64_t startUs{}; int64_t endUs{}; };
struct Song { std::string path; std::vector<Segment> segments; double gainDb{}; };
struct Export {
    std::vector<Song> songs;
    bool normalize{true};
    double targetLufs{-16};
    double truePeakDb{-1.5};
    double lra{11};
    int bitRate{192000};
    int sampleRate{48000};
    int fadeMs{0};
};

Metadata probe(const std::string &path, Job &job);
void waveform(const std::string &path, const std::string &output, Job &job);
void extract(const std::string &path, const std::string &output, Job &job);
void exportProject(const Export &project, const std::string &output, const std::string &cacheDir, Job &job);

} // namespace audioassistant
