package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;

/**
 * Son N video paketini RAM'de tutan dairesel tampon.
 *
 * Buffer her zaman bir keyframe ile başlar; böylece içerik her an decode edilebilir.
 * Bu yüzden eski paketler GOP (keyframe'den keyframe'e) bazında silinir ve
 * buffer'daki frame sayısı kısa süreliğine N'nin altına düşebilir.
 *
 * Thread-safe'tir: bir thread add() yaparken diğerleri snapshot() alabilir.
 */
public final class PacketRingBuffer implements AutoCloseable {

    private final int capacity;
    private final ArrayDeque<AVPacket> packets = new ArrayDeque<>();
    private long totalBytes;

    public PacketRingBuffer(int capacityFrames) {
        if (capacityFrames <= 0) throw new IllegalArgumentException("capacity > 0 olmalı");
        this.capacity = capacityFrames;
    }

    /** Paketin bir kopyasını ekler. Orijinal paket çağırana aittir. */
    public synchronized void add(AVPacket pkt) {
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
