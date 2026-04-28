#include <jni.h>
#include <pthread.h>
#include <stddef.h>
#include <cstring>
#include <cstdlib>

#include "lib/raop.h"
#include "log.h"
#include "lib/stream.h"
#include "lib/logger.h"

static JavaVM* g_JavaVM;
static pthread_key_t g_JniEnvKey;
static pthread_once_t g_JniEnvKeyOnce = PTHREAD_ONCE_INIT;

struct raop_jni_context {
    jobject server;
    jmethodID on_recv_audio_data;
    jmethodID on_recv_video_data;
};

static void DetachJniThread(void*) {
    if (g_JavaVM != NULL) {
        g_JavaVM->DetachCurrentThread();
    }
}

static void CreateJniEnvKey() {
    pthread_key_create(&g_JniEnvKey, DetachJniThread);
}

static JNIEnv* GetJniEnv() {
    JNIEnv* env = NULL;
    if (g_JavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }

    pthread_once(&g_JniEnvKeyOnce, CreateJniEnvKey);
    if (g_JavaVM->AttachCurrentThread(&env, NULL) != JNI_OK) {
        return NULL;
    }
    pthread_setspecific(g_JniEnvKey, reinterpret_cast<void*>(1));
    return env;
}

static void* CopyPacketData(const void* data, size_t size) {
    void* buffer = malloc(size);
    if (buffer == NULL) {
        return NULL;
    }
    memcpy(buffer, data, size);
    return buffer;
}

void OnRecvAudioData(void *observer, pcm_data_struct *data) {
    raop_jni_context* context = static_cast<raop_jni_context*>(observer);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL) {
        return;
    }

    const int byteSize = data->data_len * static_cast<int>(sizeof(unsigned short));
    void* buffer = CopyPacketData(data->data, static_cast<size_t>(byteSize));
    if (buffer == NULL) {
        return;
    }
    jobject byteBuffer = jniEnv->NewDirectByteBuffer(buffer, byteSize);
    if (byteBuffer == NULL) {
        free(buffer);
        return;
    }
    jniEnv->CallVoidMethod(context->server, context->on_recv_audio_data, byteBuffer, byteSize,
                           reinterpret_cast<jlong>(buffer), static_cast<jlong>(data->pts));
    jniEnv->DeleteLocalRef(byteBuffer);
}


void OnRecvVideoData(void *observer, h264_decode_struct *data) {
    raop_jni_context* context = static_cast<raop_jni_context*>(observer);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL) {
        return;
    }

    void* buffer = CopyPacketData(data->data, static_cast<size_t>(data->data_len));
    if (buffer == NULL) {
        return;
    }
    jobject byteBuffer = jniEnv->NewDirectByteBuffer(buffer, data->data_len);
    if (byteBuffer == NULL) {
        free(buffer);
        return;
    }
    jniEnv->CallVoidMethod(context->server, context->on_recv_video_data, byteBuffer, data->data_len,
                           reinterpret_cast<jlong>(buffer), data->frame_type,
                           static_cast<jlong>(data->nTimeStamp), static_cast<jlong>(data->pts));
    jniEnv->DeleteLocalRef(byteBuffer);
}

extern "C" void
audio_process(void *cls, pcm_data_struct *data)
{
    OnRecvAudioData(cls, data);
}

extern "C" void
audio_set_volume(void *cls, void *opaque, float volume)
{

}

extern "C" void
video_process(void *cls, h264_decode_struct *data)
{
    OnRecvVideoData(cls, data);
}

extern "C" void
log_callback(void *cls, int level, const char *msg) {
    switch (level) {
        case LOGGER_WARNING: {
            LOGW("%s", msg);
            break;
        }
        case LOGGER_ERR: {
            LOGE("%s", msg);
            break;
        }
        default:break;
    }

}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_JavaVM = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_carmo_airplay_receiver_RaopServer_start(JNIEnv* env, jobject object) {
    raop_jni_context* context = new raop_jni_context();
    context->server = env->NewGlobalRef(object);

    jclass cls = env->GetObjectClass(object);
    context->on_recv_audio_data = env->GetMethodID(cls, "onRecvAudioData", "(Ljava/nio/ByteBuffer;IJJ)V");
    context->on_recv_video_data = env->GetMethodID(cls, "onRecvVideoData", "(Ljava/nio/ByteBuffer;IJIJJ)V");
    env->DeleteLocalRef(cls);

    if (context->server == NULL || context->on_recv_audio_data == NULL || context->on_recv_video_data == NULL) {
        if (context->server != NULL) {
            env->DeleteGlobalRef(context->server);
        }
        delete context;
        return 0;
    }

    raop_callbacks_t raop_cbs;
    memset(&raop_cbs, 0, sizeof(raop_cbs));
    raop_cbs.cls = context;
    raop_cbs.audio_process = audio_process;
    raop_cbs.audio_set_volume = audio_set_volume;
    raop_cbs.video_process = video_process;

    raop_t *raop = raop_init(10, &raop_cbs);
    if (raop == NULL) {
        LOGE("raop = NULL");
        env->DeleteGlobalRef(context->server);
        delete context;
        return 0;
    }

    raop_set_log_callback(raop, log_callback, NULL);
    raop_set_log_level(raop, RAOP_LOG_WARNING);

    unsigned short port = 0;
    raop_start(raop, &port);
    raop_set_port(raop, port);
    LOGD("raop port = % d", raop_get_port(raop));
    return reinterpret_cast<jlong>(raop);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_carmo_airplay_receiver_RaopServer_getPort(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = reinterpret_cast<raop_t *>(opaque);
    return raop_get_port(raop);
}

extern "C" JNIEXPORT void JNICALL
Java_io_carmo_airplay_receiver_RaopServer_stop(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = reinterpret_cast<raop_t *>(opaque);
    raop_jni_context* context = static_cast<raop_jni_context*>(raop_get_callback_cls(raop));
    raop_destroy(raop);
    if (context != NULL) {
        env->DeleteGlobalRef(context->server);
        delete context;
    }
    LOGD("raop stopped");
}

extern "C" JNIEXPORT void JNICALL
Java_io_carmo_airplay_receiver_model_NativeMemory_free(JNIEnv* env, jobject object, jlong pointer) {
    (void) env;
    (void) object;
    if (pointer != 0) {
        free(reinterpret_cast<void*>(pointer));
    }
}
