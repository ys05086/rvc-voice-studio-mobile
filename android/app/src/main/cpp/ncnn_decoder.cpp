// JNI wrapper for NcnnDecoder.kt: the voice decoder on ncnn, on the GPU through Vulkan in 32 bits.
#include <jni.h>

#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "gpu.h"
#include "net.h"

namespace {

std::once_flag gpu_once;
bool gpu_ok = false;

struct Decoder {
    ncnn::Net net;
    bool gpu = false;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_voicestudio_phone_NcnnDecoder_nativeCreate(JNIEnv* env, jclass, jstring param, jstring model, jboolean use_gpu,
                                                     jint threads) {
    if (use_gpu) {
        std::call_once(gpu_once, [] { gpu_ok = ncnn::create_gpu_instance() == 0 && ncnn::get_gpu_count() > 0; });
    }
    auto* d = new Decoder();
    d->gpu = use_gpu && gpu_ok;
    ncnn::Option& opt = d->net.opt;
    opt.use_vulkan_compute = d->gpu;
    // 32 bits throughout: in fp16 the decoder's error (~40 dB below the signal) is audible
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = false;
    opt.use_fp16_arithmetic = false;
    opt.use_bf16_storage = false;
    opt.num_threads = threads;
    const char* p = env->GetStringUTFChars(param, nullptr);
    const char* m = env->GetStringUTFChars(model, nullptr);
    // The graph's input reshape names the piece length make_ncnn.py converted at (200 frames). Everything after it
    // is convolutions, so -1 there lets one graph take any length (realtime decodes ~50 frames a step).
    std::stringstream text;
    text << std::ifstream(p).rdbuf();
    std::string graph = text.str();
    const std::string mark = " in0 in0r 0=";
    const size_t at = graph.find(mark);
    if (at != std::string::npos) {
        const size_t from = at + mark.size();
        graph.replace(from, graph.find(' ', from) - from, "-1");
    }
    const bool bad = graph.empty() || d->net.load_param_mem(graph.c_str()) != 0 || d->net.load_model(m) != 0;
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(model, m);
    if (bad) {
        delete d;
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_voicestudio_phone_NcnnDecoder_nativeUsesGpu(JNIEnv*, jclass, jlong handle) {
    return reinterpret_cast<Decoder*>(handle)->gpu;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_voicestudio_phone_NcnnDecoder_nativeDecode(JNIEnv* env, jclass, jlong handle, jfloatArray z, jint frames,
                                                     jfloatArray sine) {
    auto* d = reinterpret_cast<Decoder*>(handle);
    const jsize nz = env->GetArrayLength(z);
    const jsize ns = env->GetArrayLength(sine);
    if (nz != 192 * frames || ns != frames * 480) return nullptr;
    std::vector<float> zb(nz), sb(ns);
    env->GetFloatArrayRegion(z, 0, nz, zb.data());
    env->GetFloatArrayRegion(sine, 0, ns, sb.data());
    ncnn::Mat mz(frames, 192, zb.data());  // [192, frames]: w = frames, h = channels
    ncnn::Mat ms(1, ns, sb.data());        // [samples, 1]
    ncnn::Mat out;
    {
        ncnn::Extractor ex = d->net.create_extractor();
        ex.input("in0", mz);
        ex.input("in1", ms);
        if (ex.extract("out0", out) != 0) return nullptr;
    }
    const jsize n = static_cast<jsize>(static_cast<size_t>(out.w) * out.h * out.c);  // one channel: contiguous
    jfloatArray result = env->NewFloatArray(n);
    env->SetFloatArrayRegion(result, 0, n, static_cast<const float*>(out.data));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicestudio_phone_NcnnDecoder_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Decoder*>(handle);
}
