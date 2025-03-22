#include <jni.h>
#include <srt.h>
#include <android/log.h>

#define LOG_TAG "SRT_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {
    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_init(JNIEnv *env, jobject thiz) {
        return srt_startup();
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_cleanup(JNIEnv *env, jobject thiz) {
        return srt_cleanup();
    }
}

