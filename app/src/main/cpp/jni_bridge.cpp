#include "engine.hpp"
#include "json.hpp"

#include <jni.h>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>

namespace {
using namespace audioassistant;
std::mutex registryMutex;
std::mutex jobMutex;
std::map<jlong, std::shared_ptr<Job>> registry;
jlong nextHandle = 1;

std::shared_ptr<Job> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = registry.find(handle);
    if (found == registry.end()) throw std::runtime_error("Native audio engine is closed");
    return found->second;
}
std::string fromJava(JNIEnv *env, jstring value) {
    if (!value) throw std::runtime_error("Null native audio request");
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::bad_alloc();
    struct ReleaseChars {
        JNIEnv *env; jstring value; const jchar *chars;
        ~ReleaseChars() { env->ReleaseStringChars(value, chars); }
    } release{env, value, chars};
    const jsize length = env->GetStringLength(value);
    std::string result;
    result.reserve(length);
    for (jsize index = 0; index < length; ++index) {
        uint32_t code = chars[index];
        if (code >= 0xd800 && code <= 0xdbff && index + 1 < length && chars[index + 1] >= 0xdc00 && chars[index + 1] <= 0xdfff)
            code = 0x10000 + ((code - 0xd800) << 10) + (chars[++index] - 0xdc00);
        if (code < 0x80) result += char(code);
        else if (code < 0x800) { result += char(0xc0 | (code >> 6)); result += char(0x80 | (code & 63)); }
        else if (code < 0x10000) { result += char(0xe0 | (code >> 12)); result += char(0x80 | ((code >> 6) & 63)); result += char(0x80 | (code & 63)); }
        else { result += char(0xf0 | (code >> 18)); result += char(0x80 | ((code >> 12) & 63)); result += char(0x80 | ((code >> 6) & 63)); result += char(0x80 | (code & 63)); }
    }
    return result;
}
void javaError(JNIEnv *env, const char *kind, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass(kind);
    if (type) env->ThrowNew(type, message);
}
int64_t integer(const Json &value) {
    double number = value.num();
    // Microsecond times beyond 2^53 cannot be represented by this JSON reader.
    if (std::floor(number) != number || std::abs(number) > 9007199254740991.0)
        throw std::runtime_error("Native integer is outside the supported range");
    return int64_t(number);
}
int smallInteger(const Json &value) {
    int64_t number = integer(value);
    if (number < std::numeric_limits<int>::min() || number > std::numeric_limits<int>::max())
        throw std::runtime_error("Native setting is outside the supported range");
    return int(number);
}
Export parseProject(const Json &value) {
    Export result;
    const auto &settings = value.at("settings");
    result.normalize = settings.at("normalize").flag();
    result.targetLufs = settings.at("targetLufs").num();
    result.truePeakDb = settings.at("truePeakDb").num();
    result.lra = settings.at("lra").num();
    result.bitRate = smallInteger(settings.at("bitRate"));
    result.sampleRate = smallInteger(settings.at("sampleRate"));
    result.fadeMs = smallInteger(settings.at("fadeMs"));
    const auto &songs = value.at("songs");
    if (songs.type != Json::Array) throw std::runtime_error("Songs must be a JSON array");
    for (const auto &item : songs.array) {
        Song song;
        song.path = item.at("path").str();
        song.gainDb = item.at("gainDb").num();
        const auto &segments = item.at("segments");
        if (segments.type != Json::Array) throw std::runtime_error("Segments must be a JSON array");
        for (const auto &segment : segments.array)
            song.segments.push_back({integer(segment.at("startUs")), integer(segment.at("endUs"))});
        result.songs.push_back(std::move(song));
    }
    return result;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_audioassistant_engine_NativeBridge_create(JNIEnv *env, jobject) {
    try {
        std::lock_guard<std::mutex> lock(registryMutex);
        jlong handle = nextHandle++;
        registry.emplace(handle, std::make_shared<audioassistant::Job>());
        return handle;
    } catch (const std::exception &error) { javaError(env, "java/lang/IllegalStateException", error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_audioassistant_engine_NativeBridge_prepare(JNIEnv *env, jobject, jlong handle) {
    try { lookup(handle)->cancelled.store(false, std::memory_order_relaxed); }
    catch (const std::exception &error) { javaError(env, "java/lang/IllegalStateException", error.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_audioassistant_engine_NativeBridge_cancel(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = registry.find(handle);
    if (found != registry.end()) found->second->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_audioassistant_engine_NativeBridge_destroy(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = registry.find(handle);
    if (found != registry.end()) {
        found->second->cancelled.store(true, std::memory_order_relaxed);
        // Active runs own a shared_ptr. Removing the handle never frees their
        // cancellation flag or interrupt_callback opaque pointer prematurely.
        registry.erase(found);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_audioassistant_engine_NativeBridge_run(JNIEnv *env, jobject, jlong handle, jstring request, jobject listener) {
    std::shared_ptr<audioassistant::Job> job;
    try {
        job = lookup(handle);
        std::unique_lock<std::mutex> serial(jobMutex, std::try_to_lock);
        if (!serial.owns_lock()) throw std::runtime_error("Another native audio job is already running");
        job->check();
        jmethodID progress = nullptr;
        if (listener) {
            jclass type = env->GetObjectClass(listener);
            progress = env->GetMethodID(type, "onProgress", "(D)V");
            env->DeleteLocalRef(type);
            if (!progress) throw std::runtime_error("Missing native progress callback");
        }
        double lastProgress = -1;
        job->progress = [env, listener, progress, &lastProgress](double value) {
            if (!listener || value < lastProgress || (value < 1 && value - lastProgress < 0.002)) return;
            lastProgress = value;
            env->CallVoidMethod(listener, progress, value);
            if (env->ExceptionCheck()) throw std::runtime_error("Native progress callback failed");
        };
        struct ClearCallback { audioassistant::Job &job; ~ClearCallback() { job.progress = {}; } } clear{*job};
        Json value = Json::parse(fromJava(env, request));
        std::string operation = value.at("operation").str();
        std::string response = "{}";
        job->report(0);
        if (operation == "probe") {
            auto metadata = audioassistant::probe(value.at("input").str(), *job);
            std::ostringstream json;
            json << "{\"durationUs\":" << metadata.durationUs << ",\"sampleRate\":" << metadata.sampleRate
                 << ",\"channels\":" << metadata.channels << ",\"codec\":" << Json::quote(metadata.codec) << '}';
            response = json.str();
        } else if (operation == "waveform") {
            audioassistant::waveform(value.at("input").str(), value.at("output").str(), *job);
        } else if (operation == "extract") {
            audioassistant::extract(value.at("input").str(), value.at("output").str(), *job);
        } else if (operation == "export") {
            audioassistant::exportProject(parseProject(value.at("project")), value.at("output").str(), value.at("cacheDir").str(), *job);
        } else throw std::runtime_error("Unknown native audio operation");
        job->report(1);
        return env->NewStringUTF(response.c_str());
    } catch (const audioassistant::Cancelled &error) {
        javaError(env, "java/util/concurrent/CancellationException", error.what());
    } catch (const std::exception &error) {
        javaError(env, "java/lang/IllegalStateException", error.what());
    } catch (...) { javaError(env, "java/lang/IllegalStateException", "Unknown native audio failure"); }
    return nullptr;
}
