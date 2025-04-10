#include <jni.h>
#include <srt.h>
#include <android/log.h>
#include <netdb.h>

#define LOG_TAG "SRT_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<int> srtInitCount(0);

extern "C" {

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtInit(JNIEnv*, jobject) {
        if (srtInitCount.fetch_add(1) == 0) {
            LOGI("Initializing SRT library");
            int ret = srt_startup();
            if (ret != 0) {
                // Startup failed, revert the counter increment
                srtInitCount.fetch_sub(1);
                LOGE("SRT startup failed with error code: %d", ret);
            }
            return ret;
        }
        return 0;
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtCreateSocket(JNIEnv*, jobject) {
        SRTSOCKET sock = srt_create_socket();
        if (sock == SRT_INVALID_SOCK) {
            LOGE("Socket creation error: %s", srt_getlasterror_str());
            return -1;
        }
        // Latency will be provided by the caller
        // Essential caller mode configuration
        int yes = 1;
        srt_setsockopt(sock, 0, SRTO_SENDER, &yes, sizeof(yes));
        int latency = 125; // 125ms latency
        srt_setsockopt(sock, 0, SRTO_LATENCY, &latency, sizeof(latency));

        return static_cast<jint>(sock);
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtConnect(JNIEnv* env, jobject, jint sock, jstring host, jint port) {
        const char* host_cstr = env->GetStringUTFChars(host, nullptr);
        char port_str[16];
        snprintf(port_str, sizeof(port_str), "%d", port);

        addrinfo hints = {};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_DGRAM;

        addrinfo* res;
        int status = getaddrinfo(host_cstr, port_str, &hints, &res);
        if (status != 0) {
            LOGE("DNS resolution failed: %s", gai_strerror(status));
            env->ReleaseStringUTFChars(host, host_cstr);
            return SRT_ERROR;
        }

        int conn_res = SRT_ERROR;
        for (auto* ai = res; ai != nullptr; ai = ai->ai_next) {
            conn_res = srt_connect(sock, ai->ai_addr, ai->ai_addrlen);
            if (conn_res != SRT_ERROR) break;
        }

        freeaddrinfo(res);
        env->ReleaseStringUTFChars(host, host_cstr);
        return conn_res;
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtSend(JNIEnv *env, jobject thiz, jint sock, jbyteArray data, jint size) {
        jbyte *nativeData = env->GetByteArrayElements(data, nullptr);
        int sent = srt_send(sock, reinterpret_cast<const char*>(nativeData), size);
        env->ReleaseByteArrayElements(data, nativeData, 0);
        return sent;
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtCleanup(JNIEnv*, jobject) {
        if (srtInitCount.fetch_sub(1) == 1) {
            LOGI("Cleaning up SRT library");
            return srt_cleanup();
        }
        return 0;
    }

    JNIEXPORT jint JNICALL Java_com_example_cameralink_streaming_SrtWrapper_srtClose(JNIEnv *env, jobject thiz, jint sock) {
        return srt_close(sock);
    }
}

