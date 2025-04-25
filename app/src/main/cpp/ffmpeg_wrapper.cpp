#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <mutex>

extern "C" {

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/rational.h>
#include <libavutil/error.h>
#include <libavcodec/bsf.h>
}

#define LOG_TAG "FFmpegWrapper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define AVIO_BUFFER_SIZE 32768  // 32KB (Point 1)

struct MuxerContext {
    AVFormatContext* fmt_ctx = nullptr;
    AVIOContext* avio_ctx = nullptr;
    AVStream* stream = nullptr;
    AVBSFContext* bsf_ctx = nullptr;
    std::vector<uint8_t> output_buffer;
    std::mutex buffer_mutex;
    int frame_rate = 0;
};

static void free_avio(MuxerContext* ctx) {
    if (ctx->avio_ctx) {
        avio_context_free(&ctx->avio_ctx);  // Handles buffer (Point 2)
    }
}

static int write_packet(void* opaque, uint8_t* buf, int buf_size) {
    auto* ctx = static_cast<MuxerContext*>(opaque);
    if (buf_size <= 0) return 0;

    std::lock_guard<std::mutex> lock(ctx->buffer_mutex);
    ctx->output_buffer.insert(ctx->output_buffer.end(), buf, buf + buf_size);
    return buf_size;
}

extern "C" {
    JNIEXPORT jlong JNICALL Java_com_example_cameralink_streaming_FfmpegWrapper_initMuxer(
        JNIEnv *env, jobject thiz, jint width, jint height, jint frame_rate,
        jint bitrate, jbyteArray extradata_)
    {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        auto* ctx = new MuxerContext();
        ctx->frame_rate = frame_rate;

        // Allocate format context
        AVFormatContext* fmt_ctx = nullptr;
        int ret = avformat_alloc_output_context2(&fmt_ctx, nullptr, "mpegts", nullptr);
        if (ret < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("Format alloc failed: %s", errbuf);
            delete ctx;
            return 0;
        }
        fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

        // Setup AVIO with larger buffer (Point 1)
        auto* avio_buffer = static_cast<uint8_t*>(av_malloc(AVIO_BUFFER_SIZE));
        ctx->avio_ctx = avio_alloc_context(avio_buffer, AVIO_BUFFER_SIZE, 1, ctx,
                                           nullptr, (int (*)(void *, const uint8_t *, int))write_packet, nullptr);
        if (!ctx->avio_ctx) {
            LOGE("AVIO alloc failed");
            avformat_free_context(fmt_ctx);
            delete ctx;
            return 0;
        }
        fmt_ctx->pb = ctx->avio_ctx;

        // Create stream
        AVStream* stream = avformat_new_stream(fmt_ctx, nullptr);
        if (!stream) {
            LOGE("Stream creation failed");
            avformat_free_context(fmt_ctx);
            fmt_ctx = nullptr;  // Point 6
            free_avio(ctx);
            delete ctx;
            return 0;
        }

        // Configure stream (Point 7)
        stream->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
        stream->codecpar->codec_id = AV_CODEC_ID_HEVC;
        stream->codecpar->width = width;
        stream->codecpar->height = height;
        stream->codecpar->bit_rate = bitrate;
        stream->time_base = (AVRational){1, 90000};  // TS standard 90kHz (Point 3)

        // Initialize bitstream filter
        const AVBitStreamFilter *bsf = av_bsf_get_by_name("hevc_mp4toannexb");
        if (!bsf) {
            LOGE("BSF not found");
            avformat_free_context(fmt_ctx);
            fmt_ctx = nullptr;
            free_avio(ctx);
            delete ctx;
            return 0;
        }

        ret = av_bsf_alloc(bsf, &ctx->bsf_ctx);
        if (ret < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("BSF alloc failed: %s", errbuf);
            avformat_free_context(fmt_ctx);
            fmt_ctx = nullptr;
            free_avio(ctx);
            delete ctx;
            return 0;
        }
        avcodec_parameters_copy(ctx->bsf_ctx->par_in, stream->codecpar);
        av_bsf_init(ctx->bsf_ctx);

        // Handle extradata
        if (extradata_ != nullptr) {
            jsize len = env->GetArrayLength(extradata_);
            jbyte* extradata = env->GetByteArrayElements(extradata_, nullptr);

            stream->codecpar->extradata = static_cast<uint8_t*>(
                    av_malloc(len + AV_INPUT_BUFFER_PADDING_SIZE));
            memcpy(stream->codecpar->extradata, extradata, len);
            stream->codecpar->extradata_size = len;
            memset(stream->codecpar->extradata + len, 0, AV_INPUT_BUFFER_PADDING_SIZE);

            env->ReleaseByteArrayElements(extradata_, extradata, JNI_ABORT);
        }

        // Write header
        ret = avformat_write_header(fmt_ctx, nullptr);
        if (ret < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("Header write failed: %s", errbuf);
            avformat_free_context(fmt_ctx);
            fmt_ctx = nullptr;
            free_avio(ctx);
            delete ctx;
            return 0;
        }

        ctx->fmt_ctx = fmt_ctx;
        ctx->stream = stream;
        return reinterpret_cast<jlong>(ctx);
    }

    JNIEXPORT jbyteArray JNICALL Java_com_example_cameralink_streaming_FfmpegWrapper_writeFrame(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray data_, jint size,
        jlong presentation_time_us, jboolean is_key_frame
        )
    {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        auto* ctx = reinterpret_cast<MuxerContext*>(handle);
        if (!ctx || !ctx->fmt_ctx) return nullptr;

        jbyte* data = env->GetByteArrayElements(data_, nullptr);
        if (!data) return nullptr;

        AVPacket* pkt = av_packet_alloc();
        if (!pkt) {
            LOGE("Packet alloc failed");
            env->ReleaseByteArrayElements(data_, data, JNI_ABORT);
            return nullptr;
        }

        // Create and populate packet
        int ret = av_new_packet(pkt, size);
        if (ret < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("Packet creation failed: %s", errbuf);
            av_packet_free(&pkt);
            env->ReleaseByteArrayElements(data_, data, JNI_ABORT);
            return nullptr;
        }
        memcpy(pkt->data, data, size);

        // Convert to Annex-B (Point 4)
        ret = av_bsf_send_packet(ctx->bsf_ctx, pkt);
        av_packet_unref(pkt);
        env->ReleaseByteArrayElements(data_, data, JNI_ABORT);

        if (ret < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("BSF send failed: %s", errbuf);
            av_packet_free(&pkt);
            return nullptr;
        }

        std::vector<AVPacket*> filtered_packets;
        while (true) {  // Changed to infinite loop
            AVPacket* filtered_pkt = av_packet_alloc();
            ret = av_bsf_receive_packet(ctx->bsf_ctx, filtered_pkt);

            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                av_packet_free(&filtered_pkt);
                break;  // Normal termination
            }
            else if (ret < 0) {
                av_strerror(ret, errbuf, sizeof(errbuf));
                LOGE("BSF receive failed: %s", errbuf);
                av_packet_free(&filtered_pkt);
                return nullptr;  // Critical error
            }

            filtered_packets.push_back(filtered_pkt);
        }

        // Process all filtered packets
        bool write_success = true;
        for (AVPacket* filtered_pkt : filtered_packets) {
            // Convert PTS to 90kHz timebase
            filtered_pkt->pts = av_rescale_q(presentation_time_us,
                                             (AVRational){1, 1000000},
                                             ctx->stream->time_base);
            filtered_pkt->dts = filtered_pkt->pts;
            filtered_pkt->duration = av_rescale_q(1,
                                                  (AVRational){1, ctx->frame_rate},
                                                  ctx->stream->time_base);
            filtered_pkt->flags = is_key_frame ? AV_PKT_FLAG_KEY : 0;
            filtered_pkt->stream_index = ctx->stream->index;

            int write_ret = av_interleaved_write_frame(ctx->fmt_ctx, filtered_pkt);
            av_packet_free(&filtered_pkt);  // Free immediately after writing

            if (write_ret < 0) {
                av_strerror(write_ret, errbuf, sizeof(errbuf));
                LOGE("Write failed: %s", errbuf);
                write_success = false;
                // Continue processing to free remaining packets
            }
        }

        if (!write_success) {
            return nullptr;  // Return error after all packets processed
        }

        // Return accumulated TS data (Point 8)
        std::lock_guard<std::mutex> lock(ctx->buffer_mutex);
        jbyteArray result = nullptr;
        if (!ctx->output_buffer.empty()) {
            result = env->NewByteArray(ctx->output_buffer.size());
            env->SetByteArrayRegion(result, 0, ctx->output_buffer.size(),
                                    reinterpret_cast<jbyte*>(ctx->output_buffer.data()));
            ctx->output_buffer.clear();
        }
        return result;  // May return null if no data (caller should handle)
    }

    JNIEXPORT void JNICALL Java_com_example_cameralink_streaming_FfmpegWrapper_closeMuxer(
        JNIEnv* env, jobject thiz, jlong handle)
    {
        auto* ctx = reinterpret_cast<MuxerContext*>(handle);
        if (!ctx) return;

        // Flush remaining data
        if (ctx->fmt_ctx) {
            av_write_trailer(ctx->fmt_ctx);
            avformat_free_context(ctx->fmt_ctx);
            ctx->fmt_ctx = nullptr;  // Point 6
        }

        // Cleanup resources
        if (ctx->bsf_ctx) {
            av_bsf_free(&ctx->bsf_ctx);
            ctx->bsf_ctx = nullptr;
        }
        if (ctx->stream && ctx->stream->codecpar) {
            if (ctx->stream->codecpar->extradata) {
                av_freep(&ctx->stream->codecpar->extradata);
            }
        }
        free_avio(ctx);
        delete ctx;
    }
}