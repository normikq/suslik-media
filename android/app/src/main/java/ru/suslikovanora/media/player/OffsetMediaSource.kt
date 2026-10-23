package ru.suslikovanora.media.player

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * An uploaded voice-over shifted by its `offsetMs` inside [androidx.media3.exoplayer.source.MergingMediaSource]:
 * +2000 — its sound starts at 00:02 of the video, −5000 — its 00:05 plays at 00:00 (a studio intro skipped).
 * Every time of the wrapped source is moved by the offset (same as Media3's own package-private TimeOffsetMediaPeriod).
 * A place before the voice-over's start (+offset) is asked from the file as 00:00, and seeks always answer the place
 * asked: [androidx.media3.exoplayer.source.MergingMediaSource] throws when a child answers another one (a file without
 * an index seeks to 00:00 only); the samples before that place are decoded and dropped, as after any seek.
 */
@UnstableApi
class OffsetMediaSource(source: MediaSource, offsetMs: Long) : WrappingMediaSource(source) {
    private val offsetUs = offsetMs * 1000L

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        Period(mediaSource.createPeriod(id, allocator, maxOf(0L, startPositionUs - offsetUs)), offsetUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) = mediaSource.releasePeriod((mediaPeriod as Period).inner)

    private class Period(val inner: MediaPeriod, private val offsetUs: Long) : MediaPeriod, MediaPeriod.Callback {
        private var callback: MediaPeriod.Callback? = null
        private fun out(us: Long) = if (us == C.TIME_END_OF_SOURCE || us == C.TIME_UNSET) us else us + offsetUs
        private fun inner(us: Long) = maxOf(0L, us - offsetUs)

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            inner.prepare(this, inner(positionUs))
        }
        override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()
        override fun getTrackGroups() = inner.trackGroups
        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = inner.getStreamKeys(trackSelections)

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long
        ): Long {
            val child = Array(streams.size) { (streams[it] as Stream?)?.inner }
            inner.selectTracks(selections, mayRetainStreamFlags, child, streamResetFlags, inner(positionUs))
            for (i in streams.indices) {
                val c = child[i]
                streams[i] = when {
                    c == null -> null
                    (streams[i] as Stream?)?.inner === c -> streams[i]
                    else -> Stream(c, offsetUs)
                }
            }
            return positionUs
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = inner.discardBuffer(inner(positionUs), toKeyframe)
        override fun readDiscontinuity() = out(inner.readDiscontinuity())
        override fun seekToUs(positionUs: Long): Long { inner.seekToUs(inner(positionUs)); return positionUs }
        // the video decides where a seek lands; the voice-over follows it exactly
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters) = positionUs
        override fun getBufferedPositionUs() = out(inner.bufferedPositionUs)
        override fun getNextLoadPositionUs() = out(inner.nextLoadPositionUs)
        override fun continueLoading(loadingInfo: LoadingInfo) =
            inner.continueLoading(loadingInfo.buildUpon().setPlaybackPositionUs(inner(loadingInfo.playbackPositionUs)).build())
        override fun isLoading() = inner.isLoading
        override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(inner(positionUs))

        override fun onPrepared(mediaPeriod: MediaPeriod) { callback?.onPrepared(this) }
        override fun onContinueLoadingRequested(source: MediaPeriod) { callback?.onContinueLoadingRequested(this) }
    }

    private class Stream(val inner: SampleStream, private val offsetUs: Long) : SampleStream {
        override fun isReady() = inner.isReady
        override fun maybeThrowError() = inner.maybeThrowError()
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            val r = inner.readData(formatHolder, buffer, readFlags)
            if (r == C.RESULT_BUFFER_READ) buffer.timeUs += offsetUs
            return r
        }
        override fun skipData(positionUs: Long) = inner.skipData(maxOf(0L, positionUs - offsetUs))
    }
}
