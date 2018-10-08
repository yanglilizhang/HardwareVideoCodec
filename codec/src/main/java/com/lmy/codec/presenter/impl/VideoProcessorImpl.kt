package com.lmy.codec.presenter.impl

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.TextureView
import com.lmy.codec.decoder.AudioDecoder
import com.lmy.codec.decoder.Decoder
import com.lmy.codec.decoder.VideoDecoder
import com.lmy.codec.decoder.impl.AudioDecoderImpl
import com.lmy.codec.decoder.impl.HardVideoDecoderImpl
import com.lmy.codec.encoder.Encoder
import com.lmy.codec.entity.CodecContext
import com.lmy.codec.entity.Sample
import com.lmy.codec.entity.Track
import com.lmy.codec.helper.MuxerFactory
import com.lmy.codec.muxer.Muxer
import com.lmy.codec.pipeline.Pipeline
import com.lmy.codec.pipeline.impl.EventPipeline
import com.lmy.codec.presenter.Processor
import com.lmy.codec.texture.impl.filter.BaseFilter
import com.lmy.codec.texture.impl.filter.NormalFilter
import com.lmy.codec.util.debug_e
import com.lmy.codec.util.debug_i
import com.lmy.codec.wrapper.CameraTextureWrapper
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Created by lmyooyo@gmail.com on 2018/10/8.
 */
class VideoProcessorImpl private constructor(ctx: Context) : Processor, Decoder.OnSampleListener,
        Encoder.OnPreparedListener {
    private val filterLock = Any()
    private val context: CodecContext = CodecContext(ctx)
    private var filter: BaseFilter? = null
    private var pipeline: Pipeline? = EventPipeline.create("ImageProcessor")
    private var textureWrapper: CameraTextureWrapper? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null
    private var extractor: MediaExtractor? = null
    private var audioExtractor: MediaExtractor? = null
    private var encoder: Encoder? = null
    private var muxer: Muxer? = null
    private var videoTrack: Track? = null
    private var audioTrack: Track? = null
    private var inputPath: String? = null

    override fun onSample(decoder: Decoder, info: MediaCodec.BufferInfo, data: ByteBuffer?) {
        if (decoder == audioDecoder) {
            muxer?.writeAudioSample(Sample.wrap(info, data!!))
        } else if (decoder == this.videoDecoder) {
            debug_i("Write ${info.presentationTimeUs}")
            encoder?.onFrameAvailable(null)
        }
    }

    override fun onPrepared(encoder: Encoder) {
        encoder.start()
        videoDecoder?.start()
        audioDecoder?.start()
    }

    private fun prepareEncoder() {
        context.video.width = videoDecoder!!.getWidth()
        context.video.height = videoDecoder!!.getHeight()
        if (videoTrack!!.format.containsKey(MediaFormat.KEY_FRAME_RATE))
            context.video.fps = videoTrack!!.format.getInteger(MediaFormat.KEY_FRAME_RATE)
        if (videoTrack!!.format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL))
            context.video.iFrameInterval = videoTrack!!.format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)
        if (videoTrack!!.format.containsKey(MediaFormat.KEY_BIT_RATE))
            context.video.bitrate = videoTrack!!.format.getInteger(MediaFormat.KEY_BIT_RATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && videoTrack!!.format.containsKey(MediaFormat.KEY_PROFILE)) {
            context.video.profile = videoTrack!!.format.getInteger(MediaFormat.KEY_PROFILE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && videoTrack!!.format.containsKey(MediaFormat.KEY_LEVEL)) {
            context.video.level = videoTrack!!.format.getInteger(MediaFormat.KEY_LEVEL)
        }
        encoder = Encoder.Builder(context, filter!!.frameBufferTexture,
                textureWrapper!!.egl!!.eglContext!!)
                .setOnPreparedListener(this)
                .build()
        if (null != muxer)
            encoder?.setOnSampleListener(muxer!!)
    }

    private fun prepareMuxer() {
        if (null == muxer) {
            muxer = MuxerFactory.getMuxer(context)
            muxer?.onMuxerListener = object : Muxer.OnMuxerListener {
                override fun onError(error: Int, msg: String) {
                    debug_e("Muxer error $error, $msg")
                }
            }
        } else {
            muxer?.reset()
        }
        muxer?.addAudioTrack(audioTrack!!.format)
    }

    private fun updateTexture() {
        textureWrapper?.updateTexture()
        textureWrapper?.updateLocation(context)
    }

    private fun prepareWrapper() {
        debug_i("prepareWrapper ${context.video.width}x${context.video.height}")
        textureWrapper = CameraTextureWrapper(context.video.width, context.video.height)
        updateTexture()
    }

    private fun prepareExtractor() {
        extractor = MediaExtractor()
        audioExtractor = MediaExtractor()
        try {
            extractor?.setDataSource(this.inputPath)
            audioExtractor?.setDataSource(this.inputPath)
        } catch (e: IOException) {
            debug_e("File(${context.ioContext.path}) not found")
            return
        }
        videoTrack = Track.getVideoTrack(extractor!!)
        audioTrack = Track.getAudioTrack(audioExtractor!!)
        context.orientation = if (videoTrack!!.format.containsKey(VideoDecoder.KEY_ROTATION))
            videoTrack!!.format.getInteger(VideoDecoder.KEY_ROTATION) else 0
        if (context.isHorizontal()) {
            context.video.width = videoTrack!!.format.getInteger(MediaFormat.KEY_WIDTH)
            context.video.height = videoTrack!!.format.getInteger(MediaFormat.KEY_HEIGHT)
            context.cameraSize.width = context.video.width
            context.cameraSize.height = context.video.height
        } else {
            context.video.width = videoTrack!!.format.getInteger(MediaFormat.KEY_HEIGHT)
            context.video.height = videoTrack!!.format.getInteger(MediaFormat.KEY_WIDTH)
            context.cameraSize.width = context.video.height
            context.cameraSize.height = context.video.width
        }
    }

    private fun prepareDecoder() {
        videoDecoder = HardVideoDecoderImpl(context, videoTrack!!, textureWrapper!!.egl!!,
                textureWrapper!!.surfaceTexture!!, pipeline!!, false, this)
        videoDecoder?.prepare()
        initFilter(NormalFilter())
        if (null != audioTrack) {
            audioDecoder = AudioDecoderImpl(context, audioTrack!!, false, this)
            audioDecoder?.prepare()
//            player = AudioPlayer(audioDecoder!!.getSampleRate(), when (audioDecoder!!.getChannel()) {
//                2 -> AudioFormat.CHANNEL_OUT_STEREO
//                else -> AudioFormat.CHANNEL_OUT_MONO
//            }, AudioFormat.ENCODING_PCM_16BIT)
        } else {
            debug_i("No audio track")
        }
    }

    override fun prepare() {
        pipeline?.queueEvent(Runnable {
            prepareExtractor()
            prepareWrapper()
            prepareDecoder()
        })
    }

    override fun setInputResource(file: File) {
        if (!file.exists()) {
            debug_e("Input file is not exists")
            return
        }
        this.inputPath = file.absolutePath
    }

    override fun setPreviewDisplay(view: TextureView) {

    }

    override fun save(path: String, end: Runnable?) {
        context.ioContext.path = path
        pipeline?.queueEvent(Runnable {
            prepareMuxer()
            prepareEncoder()
            end?.run()
        })
    }

    override fun release() {
        muxer?.release()
        muxer = null
        pipeline?.queueEvent(Runnable {
            textureWrapper?.release()
            textureWrapper = null
            videoDecoder?.release()
            videoDecoder = null
            audioDecoder?.release()
            audioDecoder = null
            extractor?.release()
            extractor = null
            audioExtractor?.release()
            audioExtractor = null
            encoder?.stop()
            encoder = null
        })
        pipeline?.quit()
        pipeline = null
        context.release()
    }

    private fun initFilter(f: BaseFilter) {
        synchronized(filterLock) {
            textureWrapper!!.egl?.makeCurrent()
            filter?.release()
            filter = f
            filter?.width = videoDecoder!!.getWidth()
            filter?.height = videoDecoder!!.getHeight()
            debug_i("Camera texture: ${textureWrapper!!.getFrameBuffer()[0]}, ${textureWrapper!!.getFrameBufferTexture()[0]}")
            filter?.textureId = textureWrapper!!.getFrameBufferTexture()
            filter?.init()
        }
    }

    override fun setFilter(filter: BaseFilter) {
        pipeline?.queueEvent(Runnable {
            synchronized(filterLock) {
                initFilter(filter)
            }
        })
    }

    override fun getFilter(): BaseFilter? {
        synchronized(filterLock) {
            return filter
        }
    }

    companion object {
        fun create(ctx: Context): Processor = VideoProcessorImpl(ctx)
    }
}