// Host-only integration tests against official FFmpeg; never part of the APK.
#include "engine.hpp"
#include <cassert>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>

using namespace audioassistant;
namespace fs = std::filesystem;

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    const fs::path root(argv[1]);
    const auto mono = (root / "mono.wav").string();
    const auto stereo = (root / "stereo.wav").string();
    const auto silence = (root / "silence.wav").string();
    auto output = [&](const char *name) { return (root / name).string(); };
    Job job;
    job.progress = [](double progress) { assert(std::isfinite(progress) && progress >= 0 && progress <= 1); };
    const auto info = probe(mono, job);
    assert(info.channels == 1 && info.sampleRate == 44100 && info.durationUs == 6000000);
    waveform(mono, output("mono.awf"), job);
    std::ifstream awf(output("mono.awf"), std::ios::binary);
    char magic[4]{};
    awf.read(magic, 4);
    assert(std::string(magic, 4) == "AWF1");
    assert(fs::file_size(output("mono.awf")) == 36 + ((264600 + 1023) / 1024) * 4);
    extract(mono, output("transcoded.m4a"), job);
    assert(probe(output("transcoded.m4a"), job).codec == "aac");
    extract(output("transcoded.m4a"), output("remuxed.m4a"), job);
    if (fs::exists(output("alac.m4a"))) {
        extract(output("alac.m4a"), output("alac-remuxed.m4a"), job);
        assert(probe(output("alac-remuxed.m4a"), job).codec == "alac");
    }
    Export project;
    project.songs = {
        {mono, {{0, 1000000}, {2000000, 4000000}}, 2.0},
        {stereo, {{1000000, 4000000}}, -1.0},
    };
    project.fadeMs = 5;
    exportProject(project, output("normalized.m4a"), root.string(), job);
    auto result = probe(output("normalized.m4a"), job);
    assert(result.channels == 2 && result.sampleRate == 48000 && result.codec == "aac");
    assert(std::abs(result.durationUs - 6000000) < 30000);
    const fs::path cache = root / "loudness-v1";
    auto cacheSnapshot = [&]() {
        std::map<std::string, fs::file_time_type> files;
        for (const auto &entry : fs::directory_iterator(cache)) {
            if (entry.path().extension() == ".json") files[entry.path().string()] = entry.last_write_time();
        }
        return files;
    };
    const auto before = cacheSnapshot();
    assert(before.size() >= 2);
    exportProject(project, output("normalized-cached.m4a"), root.string(), job);
    assert(cacheSnapshot() == before); // A hit must not rewrite first-pass statistics.
    // Deliberately corrupt a measurement; it must be safely regenerated.
    const auto brokenCache = before.begin()->first;
    { std::ofstream file(brokenCache); file << "invalid cached JSON"; }
    exportProject(project, output("cache-recovered.m4a"), root.string(), job);
    { std::ifstream file(brokenCache); std::string text; std::getline(file, text); assert(text.find("version") != std::string::npos); }
    auto altered = project;
    altered.songs[0].segments[0].endUs = 500000;
    const auto countBeforeEdit = cacheSnapshot().size();
    exportProject(altered, output("edited.m4a"), root.string(), job);
    assert(cacheSnapshot().size() == countBeforeEdit + 1); // Only the edited song is remeasured.
    project.normalize = false;
    exportProject(project, output("plain.m4a"), root.string(), job);
    project.normalize = true;
    project.songs = {{silence, {{0, 4000000}}, 0}};
    exportProject(project, output("silent.m4a"), root.string(), job);
    assert(std::abs(probe(output("silent.m4a"), job).durationUs - 4000000) < 30000);
    Job cancelled;
    cancelled.progress = [&](double progress) { if (progress > 0.1) cancelled.cancelled = true; };
    bool didCancel = false;
    try { exportProject(project, output("cancelled.m4a"), root.string(), cancelled); }
    catch (const Cancelled &) { didCancel = true; }
    assert(didCancel && !fs::exists(output("cancelled.m4a")));
    for (auto &entry : fs::directory_iterator(root)) {
        assert(entry.path().filename().string().find("audio-job-") != 0);
    }
    bool failed = false;
    try { probe(output("broken.wav"), job); }
    catch (const std::exception &) { failed = true; }
    assert(failed);
    std::cout << "PASS: probe, waveform, transcode, AAC remux, multi-segment multi-song loudnorm, cache hit/recovery/invalidation, silence, gain/fade, duration, cancellation/cleanup, invalid input\n";
}
