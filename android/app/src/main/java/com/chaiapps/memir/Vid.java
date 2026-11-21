package com.chaiapps.memir;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Shortening a video without re-making it: the picture and the sound are copied as they are,
 * from the key frame at (or just before) the chosen start, up to the chosen end.
 * It takes seconds, and the quality is exactly the original.
 */
final class Vid {
    interface Prog { void at(int pct); }

    static void trim(Context c, Uri src, File out, long startUs, long endUs, Prog p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(c, src, null);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            boolean[] done = new boolean[n];
            int max = 8 << 20, live = 0;
            boolean hasVideo = false;
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (int i = 0; i < n; i++) {
                map[i] = -1;
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                boolean v = m.startsWith("video/"), a = m.startsWith("audio/");
                if (!v && !a) continue;
                try { map[i] = mux.addTrack(f); } catch (Exception e) { map[i] = -1; continue; }   // a kind of sound the MP4 file cannot hold: left out
                ex.selectTrack(i);
                live++;
                if (v) hasVideo = true;
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) max = Math.max(max, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            if (!hasVideo) throw new Exception("novideo");
            // a film shot with the phone held upright keeps its direction
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(c, src);
                String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rot != null) {
                    int d = Integer.parseInt(rot);
                    if (d == 90 || d == 180 || d == 270) mux.setOrientationHint(d);
                }
            } catch (Exception e) {
            } finally {
                try { r.release(); } catch (Exception e) { }
            }
            ex.seekTo(Math.max(0, startUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            mux.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocate(max);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long base = -1, span = Math.max(1, endUs - startUs);
            int lastPct = -1, wrote = 0;
            while (live > 0) {
                int t = ex.getSampleTrackIndex();
                if (t < 0) break;
                long ts = ex.getSampleTime();
                if (t >= n || map[t] < 0 || done[t]) { if (!ex.advance()) break; continue; }
                if (ts > endUs) { done[t] = true; live--; ex.unselectTrack(t); continue; }
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                if (base < 0) base = ts;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0, ts - base);
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(map[t], buf, info);
                wrote++;
                int pct = (int) Math.max(0, Math.min(99, (ts - startUs) * 100 / span));
                if (pct != lastPct && p != null) { lastPct = pct; p.at(pct); }
                if (!ex.advance()) break;
            }
            if (wrote == 0) throw new Exception("empty");
        } finally {
            if (mux != null) {
                if (started) { try { mux.stop(); } catch (Exception e) { } }
                try { mux.release(); } catch (Exception e) { }
            }
            try { ex.release(); } catch (Exception e) { }
        }
    }
}
