package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;

import java.io.File;
import java.util.ArrayDeque;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * RTSP'deki tüm stream'leri dinler. Video paketleri buffer'da tutulur; bir data stream'inden
 * paket geldiğinde onun DTS'ine en yakın video frame'i decode edilip diske yazılır.
 */
public final class RtspEventCapture implements AutoCloseable {

    private final String url;
    private final File outDir;
    private final VideoBuffer buffer;
    private final FrameSaver saver = new FrameSaver();
    private final Thread reader = new Thread(this::run, "rtsp-reader");
    private volatile boolean running = true;

    /** Henüz karşılığı olan video frame'i gelmemiş data DTS'leri (video time_base'inde). */
    private final ArrayDeque<Long> pending = new ArrayDeque<>();

    public RtspEventCapture(String url, int bufferFrames, File outDir) {
        this.url = url;
        this.outDir = outDir;
        this.buffer = new VideoBuffer(bufferFrames);
        reader.setDaemon(true);
    }

    public void start() {
        outDir.mkdirs();
        av_log_set_level(AV_LOG_ERROR);
        FFmpegLogCallback.set();
        reader.start();
    }

    private void run() {
        while (running) {
            AVCodecParameters par = null;
            try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(url)) {
                g.setOption("rtsp_transport", "tcp");
                g.setOption("timeout", "5000000"); // 5 sn
                g.start();

                AVFormatContext fc = g.getFormatContext();
                int v = g.getVideoStream();
                if (v < 0) throw new IllegalStateException("Akışta video yok");
                AVStream vs = fc.streams(v);
                AVRational videoTb = vs.time_base();
                par = avcodec_parameters_alloc();
                avcodec_parameters_copy(par, vs.codecpar());

                buffer.clear();   // yeniden bağlanınca eski DTS'ler geçersiz
                pending.clear();
                long lastDataTs = AV_NOPTS_VALUE;
                System.out.printf("Bağlandı: %d stream, video=#%d %s%n", fc.nb_streams(), v, g.getVideoCodecName());

                AVPacket pkt;
                while (running && (pkt = g.grabPacket()) != null) {
                    AVStream st = fc.streams(pkt.stream_index());
                    if (pkt.stream_index() == v) {
                        buffer.add(pkt);
                    } else if (st.codecpar().codec_type() == AVMEDIA_TYPE_DATA) {
                        long ts = pkt.dts() != AV_NOPTS_VALUE ? pkt.dts() : pkt.pts();
                        if (ts == AV_NOPTS_VALUE || ts == lastDataTs) continue; // parçalı mesajın devamı
                        lastDataTs = ts;
                        pending.addLast(av_rescale_q(ts, st.time_base(), videoTb));
                    } else {
                        continue; // ses vb.
                    }
                    flushPending(par);
                }
                System.err.println("Akış bitti, yeniden bağlanılıyor...");
            } catch (Exception e) {
                System.err.println("RTSP hatası: " + e.getMessage() + " — 2 sn sonra tekrar denenecek");
            } finally {
                if (par != null) avcodec_parameters_free(par);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * Bir data DTS'i ancak buffer'a ondan sonraki bir video frame'i girdiğinde işlenir;
     * böylece "en yakın" frame'in henüz gelmemiş olma ihtimali kalmaz.
     */
    private void flushPending(AVCodecParameters par) {
        long newest = buffer.newestDts();
        while (!pending.isEmpty() && newest != AV_NOPTS_VALUE && newest >= pending.peekFirst()) {
            long dataDts = pending.pollFirst();
            VideoBuffer.Gop gop = buffer.nearest(dataDts);
            if (gop == null) continue;

            String file = new File(outDir, "data_" + dataDts + "_video_" + gop.targetDts() + ".jpg").getPath();
            System.out.printf("Data DTS %d → video DTS %d (fark %+d)%n",
                    dataDts, gop.targetDts(), gop.targetDts() - dataDts);

            AVCodecParameters copy = avcodec_parameters_alloc();
            avcodec_parameters_copy(copy, par);
            saver.submit(copy, gop, file);
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        reader.interrupt();
        reader.join(7000);
        saver.close();
        buffer.close();
    }
}
