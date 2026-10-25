package ru.suslikovanora.media.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import ru.suslikovanora.media.AppLog

/**
 * Media3's video renderer that takes a new decoder instead of flushing the old one where a flush breaks it:
 *
 *  - Qualcomm HEVC (`c2.qti.hevc.decoder`, 23.10 journal, S25+ on Android 17): a few seconds after a seek the driver
 *    dropped the pictures it could not show («QC2Comp: Skipping empty output buffer»), reported them under an old
 *    number («Ignoring stale input buffer done callback: last flush index = 229, frameIndex = 229»), and the system kept
 *    them as still inside the decoder: no free input buffer, the decoder idle, the picture stood 7–10 s while the sound
 *    played. A fresh decoder has no such count to lose; it costs ~20 ms per seek.
 *  - [freshOnNextReset]: the supervisor's «restart the video» on Media3 — a new decoder at the next seek.
 */
@OptIn(UnstableApi::class)
internal class FreshDecoderVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context, codecAdapterFactory, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback,
    eventHandler, eventListener, maxDroppedFramesToNotify,
) {
    /** Set on the main thread, read on the playback thread. */
    @Volatile var freshOnNextReset = false

    override fun flushOrReleaseCodec(): Boolean {
        val asked = freshOnNextReset
        freshOnNextReset = false
        val info = codecInfo
        if (codec != null && info != null && (asked || flushBreaks(info))) {
            AppLog.write("[media3] new video decoder ${info.name} instead of a flush (${if (asked) "picture restart" else "its flush loses pictures"})")
            releaseCodec()
            return true // the caller opens the new one
        }
        return super.flushOrReleaseCodec()
    }

    companion object {
        const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50 // as in DefaultRenderersFactory

        fun flushBreaks(info: MediaCodecInfo): Boolean =
            info.name.startsWith("c2.qti.", ignoreCase = true) && info.mimeType == MimeTypes.VIDEO_H265
    }
}
