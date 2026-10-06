// JNI bridge between Kotlin and whisper.cpp.
//
// Words are rebuilt from token-level DTW timestamps (cross-attention alignment, the same technique
// faster-whisper uses on the desktop), then returned to Kotlin as UTF-8 JSON bytes. A byte array is
// used rather than a jstring because NewStringUTF takes *modified* UTF-8 and misbehaves on 4-byte
// sequences such as emoji.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "DpxWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_abort{false};

void log_callback(enum ggml_log_level level, const char *text, void *) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) return;
    __android_log_print(prio, TAG, "%s", text);
}

struct ProgressState {
    JNIEnv *env = nullptr;
    jobject listener = nullptr;
    jmethodID method = nullptr;
    int last = -1;
};

void progress_callback(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *state = static_cast<ProgressState *>(user);
    if (state == nullptr || state->listener == nullptr || progress == state->last) return;
    state->last = progress;
    state->env->CallVoidMethod(state->listener, state->method, static_cast<jint>(progress));
}

// Runs on ggml worker threads, so it must not touch JNI.
bool abort_callback(void *) { return g_abort.load(); }

whisper_alignment_heads_preset preset_for_model(const std::string &name) {
    if (name == "tiny") return WHISPER_AHEADS_TINY;
    if (name == "base") return WHISPER_AHEADS_BASE;
    if (name == "small") return WHISPER_AHEADS_SMALL;
    if (name == "medium") return WHISPER_AHEADS_MEDIUM;
    if (name == "large-v1") return WHISPER_AHEADS_LARGE_V1;
    if (name == "large-v2") return WHISPER_AHEADS_LARGE_V2;
    if (name == "large-v3") return WHISPER_AHEADS_LARGE_V3;
    if (name == "large-v3-turbo") return WHISPER_AHEADS_LARGE_V3_TURBO;
    return WHISPER_AHEADS_NONE;
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Token boundaries can split a multi-byte character; by the time a whole word is assembled it is
// complete, but anything still invalid is replaced rather than risking a bad string downstream.
std::string sanitize_utf8(const std::string &in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        unsigned char c = static_cast<unsigned char>(in[i]);
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        bool ok = len > 0 && i + len <= in.size();
        for (size_t k = 1; ok && k < len; ++k) {
            ok = (static_cast<unsigned char>(in[i + k]) & 0xC0) == 0x80;
        }
        if (ok) {
            out.append(in, i, len);
            i += len;
        } else {
            out.push_back('?');
            ++i;
        }
    }
    return out;
}

void append_json_string(std::string &out, const std::string &raw) {
    const std::string s = sanitize_utf8(raw);
    out.push_back('"');
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
}

struct WordOut {
    std::string text;
    double start = 0;
    double end = 0;
    double prob = 1;
    int tokens = 0;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_dpx_captions_whisper_WhisperLib_initContext(JNIEnv *env, jclass, jstring jPath, jstring jModelName) {
    whisper_log_set(log_callback, nullptr);

    const std::string path = jstring_to_std(env, jPath);
    const std::string modelName = jstring_to_std(env, jModelName);

    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    // DTW alignment is unsupported with flash attention and silently disabled if both are on.
    cp.flash_attn = false;

    const auto preset = preset_for_model(modelName);
    if (preset != WHISPER_AHEADS_NONE) {
        cp.dtw_token_timestamps = true;
        cp.dtw_aheads_preset = preset;
    }

    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (ctx == nullptr) LOGE("failed to load model: %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_dpx_captions_whisper_WhisperLib_freeContext(JNIEnv *, jclass, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

JNIEXPORT void JNICALL
Java_com_dpx_captions_whisper_WhisperLib_abort(JNIEnv *, jclass) {
    g_abort.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_dpx_captions_whisper_WhisperLib_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jbyteArray JNICALL
Java_com_dpx_captions_whisper_WhisperLib_transcribe(
        JNIEnv *env, jclass, jlong ptr, jfloatArray jSamples, jstring jLanguage, jstring jPrompt,
        jint threads, jint beamSize, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return nullptr;

    g_abort.store(false);

    const std::string language = jstring_to_std(env, jLanguage);
    const std::string prompt = jstring_to_std(env, jPrompt);

    whisper_full_params wp = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beamSize > 1) wp.beam_search.beam_size = beamSize;

    wp.language = language.c_str();
    wp.translate = false;
    wp.n_threads = threads > 0 ? threads : 4;
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.suppress_blank = true;
    // Keeps "[MUSIC]"-style annotations out of the captions.
    wp.suppress_nst = true;
    if (!prompt.empty()) wp.initial_prompt = prompt.c_str();

    ProgressState progress;
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        progress.env = env;
        progress.listener = listener;
        progress.method = env->GetMethodID(cls, "onProgress", "(I)V");
        wp.progress_callback = progress_callback;
        wp.progress_callback_user_data = &progress;
    }
    wp.abort_callback = abort_callback;
    wp.abort_callback_user_data = nullptr;

    // Copy the audio out rather than pinning the Java array: a pinned array blocks the garbage collector for
    // as long as it is held, and transcription holds it for minutes.
    const jsize n = env->GetArrayLength(jSamples);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jSamples, 0, n, samples.data());
    const int rc = whisper_full(ctx, wp, samples.data(), static_cast<int>(n));

    if (rc != 0 || g_abort.load()) {
        LOGE("whisper_full failed or aborted (rc=%d)", rc);
        return nullptr;
    }

    const whisper_token eot = whisper_token_eot(ctx);
    const int segments = whisper_full_n_segments(ctx);
    std::vector<WordOut> words;

    struct Tok {
        std::string text;
        double t0 = 0;    // coarse token times, seconds
        double t1 = 0;
        double dtw = -1;  // DTW time, seconds; negative when the model has no DTW preset
        float p = 1;
    };

    for (int i = 0; i < segments; ++i) {
        const double segStart = static_cast<double>(whisper_full_get_segment_t0(ctx, i)) / 100.0;
        const double segEnd = static_cast<double>(whisper_full_get_segment_t1(ctx, i)) / 100.0;
        const int tokenCount = whisper_full_n_tokens(ctx, i);

        std::vector<Tok> toks;
        for (int j = 0; j < tokenCount; ++j) {
            const whisper_token_data d = whisper_full_get_token_data(ctx, i, j);
            if (d.id >= eot) continue;
            const char *raw = whisper_full_get_token_text(ctx, i, j);
            if (raw == nullptr || raw[0] == '\0') continue;
            Tok t;
            t.text = raw;
            t.t0 = d.t0 / 100.0;
            t.t1 = d.t1 / 100.0;
            t.dtw = d.t_dtw >= 0 ? d.t_dtw / 100.0 : -1.0;
            t.p = d.p;
            toks.push_back(std::move(t));
        }

        // whisper.cpp assigns t_dtw when the alignment path *leaves* a token for the next one (its
        // bookkeeping starts at text index 0), so t_dtw is the END of a token. The start of a token is
        // therefore the previous token's t_dtw; using t_dtw as the start made every word start about
        // 0.18 s late when compared with faster-whisper on the same audio.
        auto tokenStart = [&](size_t k) {
            if (k == 0) return segStart;
            const Tok &prev = toks[k - 1];
            return prev.dtw >= 0 ? prev.dtw : toks[k].t0;
        };
        auto tokenEnd = [&](size_t k) {
            return toks[k].dtw >= 0 ? toks[k].dtw : toks[k].t1;
        };

        const size_t firstWord = words.size();
        std::vector<size_t> lastToken;  // index of each word's final token, parallel to words[firstWord..]

        for (size_t k = 0; k < toks.size(); ++k) {
            const std::string &text = toks[k].text;
            const bool startsWord = text[0] == ' ' || words.size() == firstWord;
            if (startsWord) {
                WordOut w;
                w.text = text[0] == ' ' ? text.substr(1) : text;
                w.start = tokenStart(k);
                w.prob = toks[k].p;
                w.tokens = 1;
                words.push_back(std::move(w));
                lastToken.push_back(k);
            } else {
                WordOut &w = words.back();
                w.text += text;
                w.prob = (w.prob * w.tokens + toks[k].p) / (w.tokens + 1);
                w.tokens += 1;
                lastToken.back() = k;
            }
        }

        // Starts must not run backwards (DTW occasionally wobbles); each word ends where the next begins.
        for (size_t k = firstWord + 1; k < words.size(); ++k) {
            words[k].start = std::max(words[k].start, words[k - 1].start);
        }
        for (size_t k = firstWord; k < words.size(); ++k) {
            double end = (k + 1 < words.size()) ? words[k + 1].start : std::max(tokenEnd(lastToken.back()), segEnd);
            words[k].end = std::max(end, words[k].start + 0.04);
        }
    }

    std::string json = "{\"words\":[";
    bool first = true;
    char num[96];
    for (const auto &w : words) {
        if (w.text.empty()) continue;
        if (!first) json.push_back(',');
        first = false;
        json += "{\"text\":";
        append_json_string(json, w.text);
        std::snprintf(num, sizeof(num), ",\"start\":%.3f,\"end\":%.3f,\"probability\":%.3f}", w.start, w.end, w.prob);
        json += num;
    }
    json += "]}";

    jbyteArray out = env->NewByteArray(static_cast<jsize>(json.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(json.size()),
                            reinterpret_cast<const jbyte *>(json.data()));
    return out;
}

}  // extern "C"
