package com.otaliastudios.transcoder.source;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.otaliastudios.transcoder.common.TrackType;
import com.otaliastudios.transcoder.common.TrackTypeKt;
import com.otaliastudios.transcoder.internal.utils.ISO6709LocationParser;
import com.otaliastudios.transcoder.internal.utils.Logger;
import com.otaliastudios.transcoder.internal.utils.MutableTrackMap;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static android.media.MediaMetadataRetriever.METADATA_KEY_DURATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION;
import static com.otaliastudios.transcoder.internal.utils.TrackMapKt.mutableTrackMapOf;

/**
 * A DataSource implementation that uses Android's Media APIs.
 */
public abstract class DefaultDataSource implements DataSource {

    private final static AtomicInteger ID = new AtomicInteger(0);

    // If a seek lands more than this amount before the requested position, we consider the
    // extractor seek unreliable (e.g. mkv files with missing Cues) and fall back to a manual,
    // demux-only fast-forward. The value should be bigger than any reasonable keyframe interval,
    // because with a working seek the shortfall is at most one keyframe interval by design.
    private final static long SEEK_FALLBACK_THRESHOLD_US = 30_000_000L;
    private final Logger LOG = new Logger("DefaultDataSource(" + ID.getAndIncrement() + ")");

    private final MutableTrackMap<MediaFormat> mFormat = mutableTrackMapOf(null);
    private final MutableTrackMap<Integer> mIndex = mutableTrackMapOf(null);
    private final HashSet<TrackType> mSelectedTracks = new HashSet<>();
    private final MutableTrackMap<Long> mLastTimestampUs = mutableTrackMapOf(0L, 0L);

    private MediaMetadataRetriever mMetadata = null;
    private MediaExtractor mExtractor = null;
    private long mOriginUs = Long.MIN_VALUE;
    private boolean mInitialized = false;

    private long mDontRenderRangeStart = -1L;
    private long mDontRenderRangeEnd = -1L;

    @Override
    public void initialize() {
        LOG.i("initialize(): initializing...");
        mExtractor = new MediaExtractor();
        try {
            initializeExtractor(mExtractor);
        } catch (IOException e) {
            LOG.e("Got IOException while trying to open MediaExtractor.", e);
            throw new RuntimeException(e);
        }
        mMetadata = new MediaMetadataRetriever();
        initializeRetriever(mMetadata);

        int trackCount = mExtractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            TrackType type = TrackTypeKt.getTrackTypeOrNull(format);
            if (type != null && !mIndex.has(type)) {
                mIndex.set(type, i);
                mFormat.set(type, format);
            }
        }

        mInitialized = true;

        // Debugging mOriginUs issues.
        /* LOG.v("initialize(): origin after unselect is" + mExtractor.getSampleTime());
        if (getTrackFormat(TrackType.VIDEO) != null) {
            mExtractor.selectTrack(mIndex.getVideo());
            LOG.v("initialize(): video only origin is" + mExtractor.getSampleTime());
            mExtractor.unselectTrack(mIndex.getVideo());
        }
        if (getTrackFormat(TrackType.AUDIO) != null) {
            mExtractor.selectTrack(mIndex.getAudio());
            LOG.v("initialize(): audio only origin is" + mExtractor.getSampleTime());
            mExtractor.unselectTrack(mIndex.getAudio());
        } */
    }

    /**
     * Some properties are better initialized lazily instead of during initialize().
     * For example, the origin - very important to have a timebase before reads and seeks -
     * requires the extractor tracks to be selected, which is not true in initialize.
     * Selecting and unselecting all tracks is not correct either.
     */
    private void initializeLazyProperties() {
        if (mOriginUs == Long.MIN_VALUE) {
            mOriginUs = mExtractor.getSampleTime();
        }
    }

    @Override
    public void deinitialize() {
        LOG.i("deinitialize(): deinitializing...");
        try {
            mExtractor.release();
        } catch (Exception e) {
            LOG.w("Could not release extractor:", e);
        }
        try {
            mMetadata.release();
        } catch (Exception e) {
            LOG.w("Could not release metadata:", e);
        }

        mSelectedTracks.clear();
        mOriginUs = Long.MIN_VALUE;
        mLastTimestampUs.reset(0L, 0L);
        mFormat.reset(null, null);
        mIndex.reset(null, null);
        mDontRenderRangeStart = -1;
        mDontRenderRangeEnd = -1;
        mInitialized = false;
    }

    @Override
    public boolean isInitialized() {
        return mInitialized;
    }

    @Override
    public void selectTrack(@NonNull TrackType type) {
        LOG.i("selectTrack(" + type + ")");
        if (!mSelectedTracks.contains(type)) {
            mSelectedTracks.add(type);
            mExtractor.selectTrack(mIndex.get(type));
        }
    }

    @Override
    public void releaseTrack(@NonNull TrackType type) {
        LOG.i("releaseTrack(" + type + ")");
        if (mSelectedTracks.contains(type)) {
            mSelectedTracks.remove(type);
            mExtractor.unselectTrack(mIndex.get(type));
        }
    }

    protected abstract void initializeExtractor(@NonNull MediaExtractor extractor) throws IOException;

    protected abstract void initializeRetriever(@NonNull MediaMetadataRetriever retriever);

    @Override
    public long seekTo(long desiredPositionUs) {
        initializeLazyProperties();

        boolean hasVideo = mSelectedTracks.contains(TrackType.VIDEO);
        boolean hasAudio = mSelectedTracks.contains(TrackType.AUDIO);
        LOG.i("seekTo(): seeking to " + (mOriginUs + desiredPositionUs)
                + " originUs=" + mOriginUs
                + " extractorUs=" + mExtractor.getSampleTime()
                + " externalUs=" + desiredPositionUs
                + " hasVideo=" + hasVideo
                + " hasAudio=" + hasAudio);
        if (hasVideo && hasAudio) {
            // Special case: audio can be moved to any timestamp, but video will only stop in
            // sync frames. MediaExtractor is not smart enough to sync the two tracks at the
            // video sync frame, so we must take care of this with the following trick.
            mExtractor.unselectTrack(mIndex.getAudio());
            LOG.v("seekTo(): unselected AUDIO, seeking to " + (mOriginUs + desiredPositionUs) + " (extractorUs=" + mExtractor.getSampleTime() + ")");
            mExtractor.seekTo(mOriginUs + desiredPositionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            LOG.v("seekTo(): unselected AUDIO and sought (extractorUs=" + mExtractor.getSampleTime() + ")");
            // Capture the video position BEFORE reselecting audio: on some containers (mkv),
            // selectTrack() changes getSampleTime() to the new track's first sample, and seeking
            // to that would throw away the video seek and collapse the position to ~0.
            long videoSeekUs = mExtractor.getSampleTime();
            mExtractor.selectTrack(mIndex.getAudio()); // second seek might not be needed, but should not hurt.
            LOG.v("seekTo(): reselected AUDIO, seeking to videoUs=" + videoSeekUs + " (extractorUs=" + mExtractor.getSampleTime() + ")");
            if (videoSeekUs >= 0) {
                mExtractor.seekTo(videoSeekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
            LOG.v("seekTo(): seek workaround completed. (extractorUs=" + mExtractor.getSampleTime() + ")");
        } else {
            mExtractor.seekTo(mOriginUs + desiredPositionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        maybeFastForward(mOriginUs + desiredPositionUs, hasVideo);
        mDontRenderRangeStart = mExtractor.getSampleTime();
        mDontRenderRangeEnd = mOriginUs + desiredPositionUs;
        if (mDontRenderRangeStart > mDontRenderRangeEnd) {
            // Extractor jumped beyond the requested point!
            // This can happen in edge cases because we compute mOriginUs with both tracks selected,
            // while source can later be used with a single track. E.g. audio track starts at 0,
            // video track starts at 20000, mOriginUs is 0. A seekTo(0) will give range=20000..0.
            // In this case, range should just be empty.
            mDontRenderRangeStart = mDontRenderRangeEnd; // 0..0
        }
        LOG.i("seekTo(): dontRenderRange=" +
                mDontRenderRangeStart + ".." +
                mDontRenderRangeEnd + " (" +
                (mDontRenderRangeEnd - mDontRenderRangeStart) + "us)");
        return mExtractor.getSampleTime() - mOriginUs;
    }

    /**
     * Some files can't be sought reliably by {@link MediaExtractor} - for example, mkv files
     * with missing Cues, where any seek lands at (or near) 0. Without this check, everything
     * between the landing point and the seek target would be decoded and dropped, so trimming
     * the end of a long movie would take as long as transcoding the whole movie.
     *
     * If the seek landed too far before the target, fast-forward by advancing the extractor
     * sample by sample: this is demux-only (no sample data is read, nothing is decoded) and
     * orders of magnitude faster. Since we can't rewind, the video track must then start at
     * the first sync frame at-or-after the target, so in this fallback the effective start
     * can be later than requested by up to one keyframe interval.
     */


    private void maybeFastForward(long targetUs, boolean hasVideo) {
        long landedUs = mExtractor.getSampleTime();
        if (landedUs < 0 || targetUs - landedUs <= SEEK_FALLBACK_THRESHOLD_US) return;
        LOG.w("maybeFastForward(): seek landed " + ((targetUs - landedUs) / 1000000) + "s before" +
                " the target. Extractor seeking is unreliable for this file," +
                " fast-forwarding without decoding.");
        long startRealtimeMs = System.currentTimeMillis();
        long lastLogUs = landedUs;
        while (mExtractor.getSampleTime() >= 0 && mExtractor.getSampleTime() < targetUs) {
            mExtractor.advance();
            long sampleUs = mExtractor.getSampleTime();
            if (sampleUs - lastLogUs >= 60_000_000L) {
                LOG.i("maybeFastForward(): still skipping... " + (sampleUs / 1000000) + "s /"
                        + " " + (targetUs / 1000000) + "s"
                        + " (" + ((System.currentTimeMillis() - startRealtimeMs) / 1000) + "s elapsed)");
                lastLogUs = sampleUs;
            }
        }
        if (hasVideo) {
            // Video decoding must start at a sync frame. We can't rewind to the previous one,
            // so move forward to the next video sync frame, dropping other tracks' samples
            // in between to keep audio/video aligned.
            while (mExtractor.getSampleTime() >= 0
                    && !(mExtractor.getSampleTrackIndex() == mIndex.getVideo()
                        && (mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)) {
                mExtractor.advance();
            }
        }
        if (mExtractor.getSampleTime() < 0) {
            throw new RuntimeException("Trim point could not be reached: the file can't be" +
                    " sought (target=" + targetUs + "us) and it ended before the trim point." +
                    " The file duration metadata may be wrong.");
        }
        LOG.w("maybeFastForward(): fast-forward completed in "
                + (System.currentTimeMillis() - startRealtimeMs) + "ms, landed at "
                + mExtractor.getSampleTime() + "us (target=" + targetUs + "us)");
    }

    @Override
    public boolean isDrained() {
        return mExtractor.getSampleTrackIndex() < 0;
    }

    @Override
    public boolean canReadTrack(@NonNull TrackType type) {
        return mExtractor.getSampleTrackIndex() == mIndex.get(type);
    }

    @Override
    public void readTrack(@NonNull Chunk chunk) {
        initializeLazyProperties();

        int index = mExtractor.getSampleTrackIndex();

        int position = chunk.buffer.position();
        int limit = chunk.buffer.limit();
        int read = mExtractor.readSampleData(chunk.buffer, position);
        if (read < 0) {
            throw new IllegalStateException("No samples available! Forgot to call " +
                    "canReadTrack / isDrained?");
        } else if (position + read > limit) {
            throw new IllegalStateException("MediaExtractor is not respecting the buffer limit. " +
                    "This might cause other issues down the pipeline.");
        }
        chunk.buffer.limit(position + read);
        chunk.buffer.position(position);

        chunk.keyframe = (mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        chunk.timeUs = mExtractor.getSampleTime();
        chunk.render = chunk.timeUs < mDontRenderRangeStart || chunk.timeUs >= mDontRenderRangeEnd;
        LOG.v("readTrack(): time=" + chunk.timeUs + ", render=" + chunk.render + ", end=" + mDontRenderRangeEnd);

        // We now fetch this on initialize, can't wait until here, seek might be called before.
        // if (mStartTimestampUs == Long.MIN_VALUE) mStartTimestampUs = chunk.timeUs;

        TrackType type = (mIndex.getHasAudio() && mIndex.getAudio() == index) ? TrackType.AUDIO
                : (mIndex.getHasVideo() && mIndex.getVideo() == index) ? TrackType.VIDEO
                : null;
        if (type == null) {
            throw new RuntimeException("Unknown type: " + index);
        }
        mLastTimestampUs.set(type, chunk.timeUs);
        mExtractor.advance();

        // This means that mDontRenderRangeEnd is so high, that it covers the whole video and we
        // don't render anything. Likely due to little timestamp mismatches or using seekTo(durationUs).
        // User likely wants at least the last frame. Force send it.
        if (!chunk.render && isDrained()) {
            LOG.w("Force rendering the last frame. timeUs=" + chunk.timeUs);
            chunk.render = true;
        }
    }

    @Override
    public long getPositionUs() {
        if (mOriginUs == Long.MIN_VALUE) return 0;

        // Return the fastest track.
        // This ensures linear behavior over time: if a track is behind the other,
        // this will not push down the readUs value, which might break some components
        // down the pipeline which expect a monotonically growing timestamp.
        long last = Math.max(mLastTimestampUs.getAudio(), mLastTimestampUs.getVideo());
        return last - mOriginUs;
    }

    @Nullable
    @Override
    public double[] getLocation() {
        LOG.i("getLocation()");
        String string = mMetadata.extractMetadata(METADATA_KEY_LOCATION);
        if (string != null) {
            float[] location = new ISO6709LocationParser().parse(string);
            if (location != null) {
                double[] result = new double[2];
                result[0] = (double) location[0];
                result[1] = (double) location[1];
                return result;
            }
        }
        return null;
    }

    @Override
    public int getOrientation() {
        LOG.i("getOrientation()");
        try {
            return Integer.parseInt(mMetadata.extractMetadata(METADATA_KEY_VIDEO_ROTATION));
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    @Override
    public long getDurationUs() {
        // LOG.v("getDurationUs()");
        try {
            return Long.parseLong(mMetadata.extractMetadata(METADATA_KEY_DURATION)) * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Nullable
    @Override
    public MediaFormat getTrackFormat(@NonNull TrackType type) {
        LOG.i("getTrackFormat(" + type + ")");
        return mFormat.getOrNull(type);
    }
}
