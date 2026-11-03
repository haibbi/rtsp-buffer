package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;

/**
 * Son N video paketini RAM'de tutan dairesel tampon. Sadece video paketleri eklenmelidir.
 *
 * Buffer her zaman bir keyframe ile başlar; böylece içerik her an decode edilebilir.
 * Bu yüzden eski paketler GOP (keyframe'den keyframe'e) bazında silinir ve
 * buffer'daki frame sayısı kısa süreliğine N'nin altına düşebilir.
 *
 * Thread-safe'tir: bir thread add() yaparken diğerleri snapshot alabilir.
 */
public final class PacketRingBuffer implements AutoCloseable {

    /**
     * DTS sorgusu için decode edilmesi gereken paketler.
     *
     * @param packets   hedef frame'in GOP'u: keyframe'den bir sonraki keyframe'e kadar
     *                  (B-frame yeniden sıralaması için hedeften sonraki paketler de dahil)
     * @param targetDts seçilen paketin DTS'i
     * @param targetPts seçilen paketin PTS'i
     */
    public record GopSlice(List<AVPacket> packets, long targetDts, long targetPts) {}

    private final int capacity;
    private final ArrayDeque<AVPacket> packets = new ArrayDeque<>();
    private long totalBytes;

    public PacketRingBuffer(int capacityFrames) {
        if (capacityFrames <= 0) throw new IllegalArgumentException("capacity > 0 olmalı");
        this.capacity = capacityFrames;
    }

    /** Paketin bir kopyasını ekler. Orijinal paket çağırana aittir. */
    public synchronized void add(AVPacket pkt) {
        if (pkt.size() <= 0) return;
        boolean key = isKey(pkt);

        // Buffer boşken keyframe gelene kadar bekle (decode edilemeyen paket tutma)
        if (packets.isEmpty() && !key) return;

        AVPacket copy = av_packet_clone(pkt);
        if (copy == null) return;
        packets.addLast(copy);
        totalBytes += copy.size();

        if (packets.size() > capacity) {
            evictOne();
            // Baştaki paket artık keyframe değilse sonraki keyframe'e kadar sil
            while (!packets.isEmpty() && !isKey(packets.peekFirst())) {
                evictOne();
            }
        }
    }

    /**
     * Buffer'ın anlık kopyasını döner (ref-count'lu, veri kopyalanmaz; ucuzdur).
     * Dönen paketleri işin bitince {@link #release(List)} ile serbest bırak.
     */
    public synchronized List<AVPacket> snapshot() {
        List<AVPacket> list = new ArrayList<>(packets.size());
        for (AVPacket p : packets) list.add(av_packet_clone(p));
        return list;
    }

    /** Sadece son keyframe'den itibaren olan paketleri döner (en son frame'i almak için yeter). */
    public synchronized List<AVPacket> snapshotLastGop() {
        List<AVPacket> list = new ArrayList<>();
        var it = packets.descendingIterator();
        while (it.hasNext()) {
            AVPacket p = it.next();
            list.add(0, av_packet_clone(p));
            if (isKey(p)) break;
        }
        return list;
    }

    /**
     * Verilen DTS'e en yakın paketi bulur ve onu decode etmek için gereken GOP'u döner.
     * Eşit uzaklıkta iki paket varsa eskisi seçilir. Buffer boşsa (veya hiçbir pakette
     * DTS yoksa) null döner.
     */
    public synchronized GopSlice snapshotNearestDts(long dts) {
        AVPacket[] arr = packets.toArray(new AVPacket[0]);

        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            long d = arr[i].dts();
            if (d == AV_NOPTS_VALUE) continue;
            long diff = Math.abs(d - dts);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            } else if (d > dts) {
                break; // DTS monoton artar; uzaklaşmaya başladıysak daha iyisi yok
            }
        }
        if (best < 0) return null;

        int start = best;
        while (start > 0 && !isKey(arr[start])) start--;

        int end = best + 1;
        while (end < arr.length && !isKey(arr[end])) end++;

        List<AVPacket> list = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) list.add(av_packet_clone(arr[i]));
        return new GopSlice(list, arr[best].dts(), arr[best].pts());
    }

    /** Buffer'daki en eski DTS; yoksa {@link Long#MIN_VALUE}. */
    public synchronized long oldestDts() {
        for (AVPacket p : packets) if (p.dts() != AV_NOPTS_VALUE) return p.dts();
        return Long.MIN_VALUE;
    }

    /** Buffer'daki en yeni DTS; yoksa {@link Long#MIN_VALUE}. */
    public synchronized long newestDts() {
        var it = packets.descendingIterator();
        while (it.hasNext()) {
            AVPacket p = it.next();
            if (p.dts() != AV_NOPTS_VALUE) return p.dts();
        }
        return Long.MIN_VALUE;
    }

    public static void release(List<AVPacket> list) {
        for (AVPacket p : list) av_packet_free(p);
        list.clear();
    }

    public synchronized int size() { return packets.size(); }

    public synchronized long bytes() { return totalBytes; }

    public int capacity() { return capacity; }

    /** Akış yeniden bağlandığında eski paketler yeni decoder'la uyumsuz olabilir; temizle. */
    public synchronized void clear() {
        while (!packets.isEmpty()) evictOne();
    }

    private void evictOne() {
        AVPacket old = packets.pollFirst();
        totalBytes -= old.size();
        av_packet_free(old);
    }

    private static boolean isKey(AVPacket p) {
        return (p.flags() & AV_PKT_FLAG_KEY) != 0;
    }

    @Override
    public void close() { clear(); }
}
