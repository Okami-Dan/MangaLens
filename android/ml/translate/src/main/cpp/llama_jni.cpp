// Section 5 tier 1: llama.cpp behind a deliberately tiny JNI surface.
//
// Five entry points -- load, generate, free, and two for chat templates -- and nothing
// else. Everything that can be done in Kotlin is done in Kotlin, including building the
// prompt for a known model family: see LocalLlmTranslator (Qwen3's ChatML) and
// LocalLiquidTranslator (LFM2's), which assemble it themselves rather than pulling
// llama.cpp's `common` target and its Jinja engine into the build for one string
// operation.
//
// nativeChatTemplate/nativeApplyTemplate are the one place this file *does* reach past
// core llama.h for template handling -- but still not into `common`. They wrap
// llama_model_chat_template() and llama_chat_apply_template(), which read a GGUF's own
// embedded template and recognize 60+ known families natively (see llama-chat.cpp in the
// vendored source), for LlmStore.ModelFamily.GENERIC: a custom-pasted model whose family
// is not known in advance, so nothing here can hand-write its prompt the way the two
// known families above do.
//
// The configuration here is docs/on-device-llm.md section 3, which is sized for a
// Snapdragon 778G with no usable NPU: CPU only, four threads on the big cluster, mmap on,
// mlock off, greedy sampling.

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

/// One loaded model plus its context. The Kotlin side holds this as an opaque long.
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;

    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

/// Throws IllegalStateException on the Kotlin side. Never carries a file path: the
/// message reaches a screen, and the path names the user's storage layout.
void fail(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

std::string piece(const llama_vocab *vocab, llama_token token) {
    char buffer[256];
    const int32_t written =
        llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, /*special=*/false);
    if (written < 0) return {};
    return {buffer, static_cast<size_t>(written)};
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mangareader_translate_LlamaBridge_nativeLoad(
    JNIEnv *env, jobject, jstring modelPath, jint contextSize, jint threads) {
    static bool initialised = false;
    if (!initialised) {
        // Quiet by default. llama.cpp is chatty at info level and this runs on a phone
        // where the only reader of the log is a developer looking for a failure.
        llama_log_set(
            [](ggml_log_level level, const char *text, void *) {
                if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
            },
            nullptr);
        llama_backend_init();
        initialised = true;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    auto *session = new Session();

    llama_model_params model_params = llama_model_default_params();
    // No GPU. docs/on-device-llm.md section 3: Adreno 642L is not reliably faster than
    // four A78 cores for int4 decode, and the OpenCL path is the most common source of
    // device-specific crashes.
    model_params.n_gpu_layers = 0;
    // mmap, not mlock. Paging the weights keeps a gigabyte out of the app's heap
    // accounting entirely; locking it on a phone invites the low-memory killer.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    session->model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!session->model) {
        delete session;
        fail(env, "the model file could not be loaded");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(contextSize);
    // n_batch bounds a single llama_decode() call, and the whole prompt is submitted in
    // one. The system prompt alone is ~250 tokens before a single bubble is added, so a
    // small n_batch is not a memory saving -- it is an assertion failure inside
    // llama_context::decode, which calls ggml_abort() and takes the process with it.
    // The section 3 figure of 256 describes the *ubatch*, which is what actually sizes
    // the compute buffer.
    ctx_params.n_batch = static_cast<uint32_t>(contextSize);
    ctx_params.n_ubatch = 256;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    session->ctx = llama_init_from_model(session->model, ctx_params);
    if (!session->ctx) {
        delete session;
        fail(env, "the model loaded but a context could not be created");
        return 0;
    }

    // Greedy. This is a structured-output task; sampling mostly buys malformed JSON.
    session->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(session->sampler, llama_sampler_init_greedy());

    LOGI("loaded, n_ctx=%u threads=%d", llama_n_ctx(session->ctx), threads);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mangareader_translate_LlamaBridge_nativeGenerate(
    JNIEnv *env, jobject, jlong handle, jstring promptText, jint maxTokens) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (!session) {
        fail(env, "no model is loaded");
        return nullptr;
    }

    const char *prompt = env->GetStringUTFChars(promptText, nullptr);
    const auto prompt_len = static_cast<int32_t>(strlen(prompt));
    const llama_vocab *vocab = llama_model_get_vocab(session->model);

    // Negative return is the required size. `add_special=false` because the prompt
    // already carries the model's own chat-template markers, built in Kotlin --
    // LocalLlmTranslator's Qwen3 ChatML or LocalLiquidTranslator's LFM2 ChatML, both
    // plain text this call turns into tokens via the loaded GGUF's own vocab.
    int32_t needed = -llama_tokenize(vocab, prompt, prompt_len, nullptr, 0, false, true);
    std::vector<llama_token> tokens(needed);
    const int32_t n_prompt =
        llama_tokenize(vocab, prompt, prompt_len, tokens.data(), needed, false, true);
    env->ReleaseStringUTFChars(promptText, prompt);

    if (n_prompt < 0) {
        fail(env, "the prompt could not be tokenized");
        return nullptr;
    }
    tokens.resize(n_prompt);

    const auto n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (n_prompt >= n_ctx) {
        fail(env, "this page is too long for the model's context window");
        return nullptr;
    }
    // llama_decode() asserts n_tokens <= n_batch, and an assertion inside ggml is an
    // abort() that takes the whole app down rather than an error a caller can show. This
    // condition cannot arise with the context params set in nativeLoad; the check is here
    // so that changing them is a caught exception rather than a tombstone.
    if (n_prompt > static_cast<int32_t>(llama_n_batch(session->ctx))) {
        fail(env, "the prompt is larger than the model's batch size");
        return nullptr;
    }

    // Each call is a fresh conversation. Without this the previous chunk's KV cache is
    // still in place and the model continues it instead of answering the new request.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    // Timing is not decoration here. "Slow" is not an actionable report; prefill and
    // decode have different causes and different fixes, and separating them is what says
    // whether the next thing to change is the prompt, the batch, or the model.
    const auto started = std::chrono::steady_clock::now();

    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    if (llama_decode(session->ctx, batch) != 0) {
        fail(env, "the model failed while reading the page");
        return nullptr;
    }

    const auto prefilled = std::chrono::steady_clock::now();

    std::string out;
    llama_token token = 0;
    int32_t produced = 0;
    bool hit_eog = false;
    for (; produced < maxTokens; ++produced) {
        if (n_prompt + produced >= n_ctx) break;

        token = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            hit_eog = true;
            break;
        }

        out += piece(vocab, token);

        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(session->ctx, batch) != 0) break;
    }

    const auto finished = std::chrono::steady_clock::now();
    const auto prefill_ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(prefilled - started).count();
    const auto decode_ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(finished - prefilled).count();

    LOGI("prefill %d tok in %lld ms (%.1f tok/s) | decode %d tok in %lld ms (%.1f tok/s)%s",
         n_prompt, static_cast<long long>(prefill_ms),
         prefill_ms > 0 ? n_prompt * 1000.0 / prefill_ms : 0.0,
         produced, static_cast<long long>(decode_ms),
         decode_ms > 0 ? produced * 1000.0 / decode_ms : 0.0,
         hit_eog ? " | stopped at EOG" : " | hit the token budget");
    // The first of the output, so "is it producing JSON or prose?" is answerable from a
    // log rather than by guessing at a blank tier 3.
    LOGI("output[0:180]: %.180s", out.c_str());

    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mangareader_translate_LlamaBridge_nativeChatTemplate(
    JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (!session) return nullptr;
    // Reads GGUF metadata key tokenizer.chat_template directly. null means the file has
    // none -- not an error, just information nativeApplyTemplate already acts on itself.
    const char *tmpl = llama_model_chat_template(session->model, nullptr);
    return tmpl ? env->NewStringUTF(tmpl) : nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mangareader_translate_LlamaBridge_nativeApplyTemplate(
    JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
    jboolean addAssistant) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (!session) {
        fail(env, "no model is loaded");
        return nullptr;
    }

    const jsize n = env->GetArrayLength(roles);

    // Copied into std::string first: llama_chat_message holds raw `const char *`, and a
    // jstring's UTF chars must stay valid only as long as the JNI release call has not
    // run, which would be before llama_chat_apply_template ever reads them otherwise.
    std::vector<std::string> role_text(n), content_text(n);
    for (jsize i = 0; i < n; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        const char *r = env->GetStringUTFChars(role, nullptr);
        const char *c = env->GetStringUTFChars(content, nullptr);
        role_text[i] = r;
        content_text[i] = c;
        env->ReleaseStringUTFChars(role, r);
        env->ReleaseStringUTFChars(content, c);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    std::vector<llama_chat_message> messages(n);
    for (jsize i = 0; i < n; ++i) {
        messages[i] = {role_text[i].c_str(), content_text[i].c_str()};
    }

    // null here means "the GGUF has no embedded template" and is not this function's
    // failure to detect one -- llama_chat_apply_template's own convention (verified in
    // the vendored source) is to default a null template to plain ChatML rather than
    // fail, which is why that case is not distinguished from a hit here.
    const char *tmpl = llama_model_chat_template(session->model, nullptr);

    const int32_t needed = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), addAssistant, nullptr, 0);
    if (needed < 0) {
        // The template exists but matches none of llama.cpp's known families -- the one
        // case this app has to refuse rather than guess at. Not a fail(): a genuinely
        // unrecognized template is an expected outcome for an arbitrary pasted model, not
        // a broken session, and Kotlin turns a null return into its own clear message.
        return nullptr;
    }

    std::vector<char> buf(static_cast<size_t>(needed) + 1);
    const int32_t written = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), addAssistant, buf.data(),
        static_cast<int32_t>(buf.size()));
    if (written < 0) return nullptr;

    return env->NewStringUTF(std::string(buf.data(), written).c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_mangareader_translate_LlamaBridge_nativeFree(
    JNIEnv *, jobject, jlong handle) {
    // Section 4 of the doc: unload the moment the queue drains. Holding a gigabyte
    // resident between pages is how a mid-range phone gets the app killed.
    delete reinterpret_cast<Session *>(handle);
}
