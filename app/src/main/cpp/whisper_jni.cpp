// JNI bridge between the Kotlin app and whisper.cpp
#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "whisperjni", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

static std::atomic<bool> g_abort{false};
static std::atomic<int>  g_progress{0};

static bool abort_cb(void * /*data*/) {
    return g_abort.load();
}

static void progress_cb(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/,
                        int progress, void * /*user*/) {
    g_progress.store(progress);
}

static std::string clean(const char * s) {
    std::string out = s ? s : "";
    for (char & c : out) {
        if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    }
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sajjad_transcripts_WhisperLib_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    LOGI("model loaded: %s", ctx ? "yes" : "no");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_sajjad_transcripts_WhisperLib_nativeFree(JNIEnv *, jclass, jlong ptr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_sajjad_transcripts_WhisperLib_nativeAbort(JNIEnv *, jclass, jboolean flag) {
    g_abort.store(flag == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_sajjad_transcripts_WhisperLib_nativeProgress(JNIEnv *, jclass) {
    return g_progress.load();
}

JNIEXPORT jstring JNICALL
Java_com_sajjad_transcripts_WhisperLib_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Returns UTF-8 bytes:  first line = language code,
// then one line per segment: t0_ms \t t1_ms \t text
// Returns null on failure or when aborted.
JNIEXPORT jbyteArray JNICALL
Java_com_sajjad_transcripts_WhisperLib_nativeTranscribe(JNIEnv *env, jclass, jlong ptr,
                                                        jfloatArray jsamples, jstring jlang,
                                                        jboolean translate, jint threads) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ptr);
    if (!ctx) return nullptr;

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language = lang ? lang : "auto";
    env->ReleaseStringUTFChars(jlang, lang);

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);

    struct whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads        = threads > 0 ? threads : 4;
    wp.translate        = translate == JNI_TRUE;
    wp.language         = language.c_str();
    wp.detect_language  = false;
    wp.no_context       = true;
    wp.single_segment   = false;
    wp.print_realtime   = false;
    wp.print_progress   = false;
    wp.print_timestamps = false;
    wp.print_special    = false;
    wp.suppress_blank   = true;
    wp.progress_callback = progress_cb;
    wp.progress_callback_user_data = nullptr;
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = nullptr;

    g_progress.store(0);
    int rc = whisper_full(ctx, wp, samples, n);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);

    if (rc != 0 || g_abort.load()) {
        LOGI("whisper_full rc=%d abort=%d", rc, (int) g_abort.load());
        return nullptr;
    }

    std::string out;
    int lang_id = whisper_full_lang_id(ctx);
    const char *lang_str = lang_id >= 0 ? whisper_lang_str(lang_id) : nullptr;
    out += lang_str ? lang_str : language;
    out += '\n';

    const int ns = whisper_full_n_segments(ctx);
    for (int i = 0; i < ns; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;   // ms
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        out += std::to_string(t0);
        out += '\t';
        out += std::to_string(t1);
        out += '\t';
        out += clean(whisper_full_get_segment_text(ctx, i));
        out += '\n';
    }

    jbyteArray result = env->NewByteArray((jsize) out.size());
    if (result) {
        env->SetByteArrayRegion(result, 0, (jsize) out.size(),
                                reinterpret_cast<const jbyte *>(out.data()));
    }
    return result;
}

} // extern "C"
