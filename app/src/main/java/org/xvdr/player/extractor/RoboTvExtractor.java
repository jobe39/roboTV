package org.xvdr.player.extractor;

import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TimestampAdjuster;

import org.xvdr.player.BufferPacket;
import org.xvdr.player.PositionReference;
import org.xvdr.robotv.client.Connection;
import org.xvdr.robotv.client.StreamBundle;

import java.io.IOException;

public class RoboTvExtractor implements Extractor {

    private static final String TAG = RoboTvExtractor.class.getName();

    public static class Factory implements ExtractorsFactory {

        private RoboTvExtractor extractor = null;
        final private Listener listener;
        final private PositionReference position;

        public Factory(PositionReference position, Listener listener) {
            this.listener = listener;
            this.position = position;
        }

        @Override
        public Extractor[] createExtractors() {
            extractor = new RoboTvExtractor(position, listener);
            return new Extractor[] { extractor };
        }
    }

    private class PositionSeekMap implements SeekMap {
        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public long getDurationUs() {
            return C.TIME_UNSET;
        }

        @Override
        public long getPosition(long timeUs) {
            return position.positionFromTimeUs(timeUs);
        }
    }

    public interface Listener {
        void onTracksChanged(StreamBundle bundle);
    }

    private ExtractorOutput output;

    final private ExtractorBufferPacket scratch;
    final private StreamManager streamManager;
    final private Listener listener;
    final private PositionReference position;
    final private TimestampAdjuster timestampAdjuster;


    private boolean seenFirstTimestamp = false;

    private RoboTvExtractor(PositionReference position, Listener listener) {
        this.listener = listener;
        this.position = position;
        this.scratch = new ExtractorBufferPacket(new byte[1024]);
        this.streamManager = new StreamManager();
        this.timestampAdjuster = new TimestampAdjuster(TimestampAdjuster.DO_NOT_OFFSET);
    }

    @Override
    public boolean sniff(ExtractorInput input) throws IOException, InterruptedException {
        scratch.peek(input, 2);
        int packetType = scratch.getU16();

        return (packetType == Connection.XVDR_STREAM_CHANGE || packetType == Connection.XVDR_STREAM_MUXPKT);
    }

    @Override
    public void init(ExtractorOutput output) {
        this.output = output;
        output.seekMap(new PositionSeekMap());
    }

    @Override
    synchronized public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException, InterruptedException {
        // read packet header

        // messageId  uint16  2 bytes
        // frameType  uint16  2 bytes
        // length     uint32  4 bytes

        scratch.read(input, 4);

        int messageId = scratch.getU16();
        int frameType = scratch.getU16();

        // check for format packet
        if(messageId == Connection.XVDR_STREAM_CHANGE) {
            Log.d(TAG, "stream change packet received");
            scratch.peek(input, 512);
            updateStreamReaders(scratch);

            int bytesRead = scratch.position();
            Log.d(TAG, "skipping " + bytesRead + " bytes (stream change packet)");
            input.skipFully(bytesRead);

            return RESULT_CONTINUE;
        }

        // exit if we didn't receive a stream packet
        if(messageId != Connection.XVDR_STREAM_MUXPKT) {
            Log.d(TAG, "unknown message id: " + messageId);
            return RESULT_CONTINUE;
        }

        // read stream packet header

        // pid        uint16  2 bytes
        // pts        int64   8 bytes
        // dts        int64   8 bytes
        // duration   uint32  4 bytes
        // length     uint32  4 bytes

        scratch.read(input, 26);

        int pid = scratch.getU16();
        long pts = scratch.getS64();
        long dts = scratch.getS64();
        scratch.getU32();

        int size = (int) scratch.getU32(); // size of encapsulated stream data

        StreamReader reader = streamManager.get(pid);

        // unknown stream ?
        if(reader == null) {
            input.skipFully(size);
            input.skipFully(8);
            return RESULT_CONTINUE;
        }

        // register first DTS (to compute to offset)
        if(!seenFirstTimestamp) {
            timestampAdjuster.adjustTsTimestamp(dts);
            seenFirstTimestamp = true;
        }

        // convert PTS -> timeUs
        long timeUs = timestampAdjuster.adjustTsTimestamp(pts);

        // consume stream data
        reader.consume(input, size, timeUs, C.BUFFER_FLAG_KEY_FRAME);

        // get current position
        // position   uint64  8 bytes

        scratch.read(input, 8);

        long pos = scratch.getU64();

        // sanity check if position is within the range
        if(position.getStartPosition() < pos && pos < position.getEndPosition()) {
            position.set(timeUs, pos);
        }

        return RESULT_CONTINUE;
    }

    @Override
    synchronized public void seek(long p, long timeUs) {
        Log.d(TAG, "seek: " + p);
        timestampAdjuster.reset();
        seenFirstTimestamp = false;
    }

    @Override
    public void release() {
    }

    private void updateStreamReaders(BufferPacket p) {
        final StreamBundle bundle = new StreamBundle();
        bundle.updateFromPacket(p);

        if(streamManager.size() == 0) {
            Log.d(TAG, "create streams");
            streamManager.createStreams(output, bundle);
        }
        else {
            Log.d(TAG, "update streams");
            streamManager.updateStreams(bundle);
        }

        if(listener != null) {
            listener.onTracksChanged(bundle);
        }
    }
}
