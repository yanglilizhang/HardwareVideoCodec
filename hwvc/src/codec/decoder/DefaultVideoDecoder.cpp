/*
* Copyright (c) 2018-present, lmyooyo@gmail.com.
*
* This source code is licensed under the GPL license found in the
* LICENSE file in the root directory of this source tree.
*/
#include "../include/DefaultVideoDecoder.h"
#include "log.h"

#ifdef __cplusplus
extern "C" {
#endif

DefaultVideoDecoder::DefaultVideoDecoder() {

}

DefaultVideoDecoder::~DefaultVideoDecoder() {
    if (avPacket) {
        av_packet_unref(avPacket);
        avPacket = nullptr;
    }
    if (aCodecContext) {
        avcodec_close(aCodecContext);
        aCodecContext = nullptr;
    }
    if (vCodecContext) {
        avcodec_close(vCodecContext);
        vCodecContext = nullptr;
    }
    if (pFormatCtx) {
        avformat_free_context(pFormatCtx);
        pFormatCtx = nullptr;
    }
}

bool DefaultVideoDecoder::prepare(string path) {
    this->path = path;
    av_register_all();
    printCodecInfo();
    pFormatCtx = avformat_alloc_context();
    //打开输入视频文件
    if (avformat_open_input(&pFormatCtx, path.c_str(), NULL, NULL) != 0) {
        LOGE("Couldn't open input stream.");
        return false;
    }
    //获取视频文件信息
    if (avformat_find_stream_info(pFormatCtx, NULL) < 0) {
        LOGE("Couldn't find stream information.");
        return -1;
    }
    for (int i = 0; i < pFormatCtx->nb_streams; i++) {
        if (-1 == videoTrack &&
            AVMediaType::AVMEDIA_TYPE_VIDEO == pFormatCtx->streams[i]->codecpar->codec_type) {
            videoTrack = i;
        }
        if (-1 == audioTrack &&
            AVMediaType::AVMEDIA_TYPE_AUDIO == pFormatCtx->streams[i]->codecpar->codec_type) {
            audioTrack = i;
        }
    }
    if (-1 != videoTrack && !openTrack(videoTrack, &vCodecContext)) {
        LOGE("******** Open video track failed. *********");
        return false;
    }
    if (-1 != audioTrack && !openTrack(audioTrack, &aCodecContext)) {
        LOGE("******** Open audio track failed. *********");
        return false;
    }
    if (-1 == videoTrack && -1 == audioTrack) {
        LOGE("******** This file not contain video or audio track. *********");
        return false;
    }
    //准备资源
    avPacket = av_packet_alloc();
    return true;
}

int DefaultVideoDecoder::grab(AVFrame *avFrame) {
    if (currentTrack == videoTrack && 0 == avcodec_receive_frame(vCodecContext, avFrame)) {
//        LOGI("avcodec_receive_frame");
        return getMediaType(currentTrack);
    } else if (currentTrack == audioTrack && 0 == avcodec_receive_frame(aCodecContext, avFrame)) {
        return getMediaType(currentTrack);
    }
    if (avPacket) {
        av_packet_unref(avPacket);
    }
    if (av_read_frame(pFormatCtx, avPacket) >= 0) {
//        LOGI("av_read_frame");
        currentTrack = avPacket->stream_index;
        //解码
        int ret = -1;
        if (videoTrack == currentTrack) {
            if ((ret = avcodec_send_packet(vCodecContext, avPacket)) == 0) {
                // 一个avPacket可能包含多帧数据，所以需要使用while循环一直读取
                return grab(avFrame);
            }
        } else if (audioTrack == currentTrack) {
            if ((ret = avcodec_send_packet(aCodecContext, avPacket)) == 0) {
                return grab(avFrame);
            }
        } else{
            return grab(avFrame);
        }
        switch (ret) {
            case AVERROR(EAGAIN): {
                LOGI("you must read output with avcodec_receive_frame");
                return grab(avFrame);
            }
            case AVERROR(EINVAL): {
                LOGI("codec not opened, it is an encoder, or requires flush");
                break;
            }
            case AVERROR(ENOMEM): {
                LOGI("failed to add packet to internal queue");
                break;
            }
            case AVERROR_EOF: {
                LOGI("eof");
                break;
            }
            default:
                LOGI("avcodec_send_packet ret=%d", ret);
        }
    }
    return MEDIA_TYPE_EOF;
}

int DefaultVideoDecoder::width() {
    if (!pFormatCtx) return 0;
    return pFormatCtx->streams[videoTrack]->codecpar->width;
}

int DefaultVideoDecoder::height() {
    if (!pFormatCtx) return 0;
    return pFormatCtx->streams[videoTrack]->codecpar->height;
}

int DefaultVideoDecoder::getMediaType(int track) {
    if (videoTrack == track) {
        return MEDIA_TYPE_VIDEO;
    }
    if (audioTrack == track) {
        return MEDIA_TYPE_AUDIO;
    }
    return MEDIA_TYPE_UNKNOWN;
}

bool DefaultVideoDecoder::openTrack(int track, AVCodecContext **context) {
    AVCodecParameters *avCodecParameters = pFormatCtx->streams[track]->codecpar;
    if (videoTrack == track) {
        LOGI("DefaultVideoDecoder(%s) %d x %d", path.c_str(), avCodecParameters->width,
             avCodecParameters->height);
    }
    AVCodec *codec = NULL;
    if (AV_CODEC_ID_H264 == avCodecParameters->codec_id) {
        codec = avcodec_find_decoder_by_name("h264_mediacodec");
        if (NULL == codec) {
            LOGE("Selected AV_CODEC_ID_H264.");
            codec = avcodec_find_decoder(avCodecParameters->codec_id);
        }
    } else {
        codec = avcodec_find_decoder(avCodecParameters->codec_id);
    }
    if (NULL == codec) {
        LOGE("Couldn't find codec.");
        return false;
    }
    //打开编码器
    *context = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(*context, avCodecParameters);
    if (avcodec_open2(*context, codec, NULL) < 0) {
        LOGE("Couldn't open codec.");
        return false;
    }
    char *typeName = "unknown";
    if (AVMEDIA_TYPE_VIDEO == codec->type) {
        typeName = "video";
    } else if (AVMEDIA_TYPE_AUDIO == codec->type) {
        typeName = "audio";
    }
    LOGI("Open %s track with %s, fmt=%d", typeName, codec->name, avCodecParameters->format);
    return true;
}

void DefaultVideoDecoder::printCodecInfo() {
    char info[1024] = {0};
    AVCodec *c_temp = av_codec_next(NULL);
    while (c_temp != NULL) {
        if (c_temp->decode != NULL) {
            sprintf(info, "%s[Dec]", info);
        } else {
            sprintf(info, "%s[Enc]", info);
        }
        switch (c_temp->type) {
            case AVMEDIA_TYPE_VIDEO:
                sprintf(info, "%s[Video]", info);
                break;
            case AVMEDIA_TYPE_AUDIO:
                sprintf(info, "%s[Audio]", info);
                break;
            default:
                sprintf(info, "%s[Other]", info);
                break;
        }
        sprintf(info, "%s[%10s]\n", info, c_temp->name);
        c_temp = c_temp->next;
    }
    LOGI("%s", info);
}

#ifdef __cplusplus
}
#endif