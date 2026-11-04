package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;

/**
 * Son N video paketini decode etmeden RAM'de tutar. Her zaman bir keyframe ile başlar,
 * bu yüzden eski paketler GOP bazında silinir.
 */
final class VideoBuffer implements AutoCloseable {

    /** Hedef frame'i decode etmek için gereken paketler (keyframe → sonraki keyframe). */
    record Gop(List<AVPacket> packets, long targetDts, long targetPts) {}

    private final int capacity;
    private final ArrayDeque<AVPacket> packets = new ArrayDeque<>();

    VideoBuffer(int capacity) {
        this.capacity = capacity;
    }

    synchronized void add(AVPacket pkt) {
        boolean key = isKey(pkt);
        if (pkt.size() <= 0 || (packets.isEmpty() && !key)) return;

        AVPacket copy = av_packet_clone(pkt);
        if (copy.dts() == AV_NOPTS_VALUE) copy.dts(copy.pts());
        if (copy.dts() == AV_NOPTS_VALUE) { av_packet_free(copy); return; }
        packets.addLast(copy);

        if (packets.size() > capacity) {
            do av_packet_free(packets.pollFirst());
            while (!packets.isEmpty() && !isKey(packets.peekFirst()));
        }
    }

    synchronized long newestDts() {
        return packets.isEmpty() ? AV_NOPTS_VALUE : packets.peekLast().dts();
    }

    /** Verilen DTS'e en yakın frame'in GOP'unu döner (paketler klonlanır). Boşsa null. */
    synchronized Gop nearest(long dts) {
        AVPacket[] arr = packets.toArray(new AVPacket[0]);
        if (arr.length == 0) return null;

        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (Math.abs(arr[i].dts() - dts) < Math.abs(arr[best].dts() - dts)) best = i;
            else if (arr[i].dts() > dts) break; // DTS artıyor, artık uzaklaşıyoruz
        }

        int start = best, end = best + 1;
        while (start > 0 && !isKey(arr[start])) start--;
        while (end < arr.length && !isKey(arr[end])) end++; // B-frame'ler için GOP sonuna kadar

        List<AVPacket> list = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) list.add(av_packet_clone(arr[i]));
        return new Gop(list, arr[best].dts(), arr[best].pts());
    }

    synchronized void clear() {
        while (!packets.isEmpty()) av_packet_free(packets.pollFirst());
    }

    static void free(List<AVPacket> list) {
        list.forEach(p -> av_packet_free(p));
    }

    private static boolean isKey(AVPacket p) {
        return (p.flags() & AV_PKT_FLAG_KEY) != 0;
    }

    @Override
    public void close() {
        clear();
    }
}
