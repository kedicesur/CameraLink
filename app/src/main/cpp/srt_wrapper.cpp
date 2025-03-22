#include <jni.h>
#include <srt.h>
#include <android/log.h>

#define LOG_TAG "SRT_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtInit(JNIEnv *env, jobject thiz) {
    return srt_startup();
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtCreateSocket(JNIEnv *env, jobject thiz) {
        int sock = srt_create_socket();
        return (sock < 0) ? -1 : sock;
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtConnect(JNIEnv *env, jobject thiz, jint sock, jstring host, jint port) {
        const char *nativeHost = env->GetStringUTFChars(host, 0);

        struct sockaddr_in sa{};
        sa.sin_family = AF_INET;
        sa.sin_port = htons(port);
        inet_pton(AF_INET, nativeHost, &sa.sin_addr);

        env->ReleaseStringUTFChars(host, nativeHost);
        return srt_connect(sock, (struct sockaddr*)&sa, sizeof(sa));
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtSend(JNIEnv *env, jobject thiz, jint sock, jbyteArray data, jint size) {
        jbyte *nativeData = env->GetByteArrayElements(data, nullptr);
        int sent = srt_send(sock, reinterpret_cast<const char*>(nativeData), size);
        env->ReleaseByteArrayElements(data, nativeData, 0);
        return sent;
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtCleanup(JNIEnv *env, jobject thiz) {
        return srt_cleanup();
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtClose(JNIEnv *env, jobject thiz, jint sock) {
        return srt_close(sock);
    }
}

