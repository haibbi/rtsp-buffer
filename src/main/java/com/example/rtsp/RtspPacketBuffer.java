package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

/**
 * RTSP akışını arka planda dinler, sadece video paketlerini decode etmeden RAM'de tutar.
 * Ses, metadata (ONVIF vb.) ve diğer data track'leri hiç açılmaz.
 * Resim istendiğinde paketleri decode eder. Kopma olursa otomatik yeniden bağlanır.
 */
public final class RtspPacketBuffer implements AutoCloseable {

    private final String url;
    private final PacketRingBuffer buffer;
    private final Thread worker;
    private volatile boolean running = true;

    /** Decoder kurmak için akışın codec bilgisi (her bağlantıda güncellenir). */
    private AVCodecParameters codecPar;
    private final Object parLock = new Object();

    private final AtomicLong receivedPackets = new AtomicLong();
    private volatile double fps;
    private volatile int width, height;
    private volatile int timeBaseNum = 1, timeBaseDen = 90000;
    private volatile boolean connected;

    public RtspPacketBuffer(String url, int capacityFrames) {
        this.url = url;
        this.buffer = new PacketRingBuffer(capacityFrames);
        this.worker = new Thread(this::run, "rtsp-reader");
        this.worker.setDaemon(true);
    }

    public void start() {
        av_log_set_level(AV_LOG_ERROR);
        FFmpegLogCallback.set();
        worker.start();
    }

    private void run() {
        while (running) {
            try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(url)) {
                g.setOption("rtsp_transport", "tcp");          // UDP'de paket kaybı olur
                g.setOption("allowed_media_types", "video");   // sadece video track'i SETUP edilir
                g.setOption("timeout", "5000000");             // 5 sn (mikrosaniye) okuma timeout'u
                g.setOption("fflags", "nobuffer");
                g.start();

                int videoStream = g.getVideoStream();
                if (videoStream < 0) throw new IllegalStateException("Akışta video yok");

                // Yine de başka stream geldiyse demuxer seviyesinde at
                AVFormatContext fc = g.getFormatContext();
                for (int i = 0; i < fc.nb_streams(); i++) {
                    if (i != videoStream) fc.streams(i).discard(AVDISCARD_ALL);
                }

                AVStream vs = fc.streams(videoStream);
                synchronized (parLock) {
                    if (codecPar != null) avcodec_parameters_free(codecPar);
                    codecPar = avcodec_parameters_alloc();
                    avcodec_parameters_copy(codecPar, vs.codecpar());
                }
                timeBaseNum = vs.time_base().num();
                timeBaseDen = vs.time_base().den();
                buffer.clear(); // eski bağlantının paketleri ve DTS'leri artık geçersiz

                fps = g.getFrameRate();
                width = g.getImageWidth();
                height = g.getImageHeight();
                connected = true;
                System.out.printf("Bağlandı: %dx%d, %.1f fps, codec=%s, time_base=%d/%d%n",
                        width, height, fps, g.getVideoCodecName(), timeBaseNum, timeBaseDen);

                AVPacket pkt;
                while (running && (pkt = g.grabPacket()) != null) {
                    if (pkt.stream_index() != videoStream) continue;
                    buffer.add(pkt);  // içeride klonlanır
                    receivedPackets.incrementAndGet();
                    // pkt'yi serbest bırakmıyoruz; grabber bir sonraki grabPacket'te kendisi yapar
                }
                System.err.println("Akış bitti, yeniden bağlanılıyor...");
            } catch (Exception e) {
                System.err.println("RTSP hatası: " + e.getMessage() + " — 2 sn sonra tekrar denenecek");
            } finally {
                connected = false;
            }
            sleepQuietly(2000);
        }
    }

    /**
     * Verilen DTS'e (stream time_base biriminde) en yakın frame'i döner.
     * Tam eşleşme yoksa en yakın olanı verir; hangisinin seçildiği sonuçta yazar.
     * Buffer boşsa null.
     */
    public TimedImage imageAtDts(long dts) {
        PacketRingBuffer.GopSlice slice = buffer.snapshotNearestDts(dts);
        if (slice == null) return null;

        long tDts = slice.targetDts(), tPts = slice.targetPts();
        TimedImage[] result = new TimedImage[1];
        int num = timeBaseNum, den = timeBaseDen;

        decode(slice.packets(), (pts, fdts, image) -> {
            // Decoder frame'leri PTS sırasıyla verir; hedefi DTS'inden (yoksa PTS'inden) tanı
            boolean dtsMatch = fdts != AV_NOPTS_VALUE && fdts == tDts;
            boolean ptsMatch = pts != AV_NOPTS_VALUE && pts == tPts;
            if (dtsMatch || ptsMatch) {
                result[0] = new TimedImage(image.get(), tDts, pts, dts, num, den);
                return false; // bulundu, gerisini decode etme
            }
            return true;      // ara frame: resme çevirmeden geç
        });
        return result[0];
    }

    /** {@link #imageAtDts(long)}'in milisaniye cinsinden kolaylık sürümü. */
    public TimedImage imageAtMillis(long millis) {
        return imageAtDts(millisToDts(millis));
    }

    /** Buffer'daki frame'leri eski→yeni sırayla tek tek işler; hepsini aynı anda RAM'e almaz. */
    public void forEachImage(Consumer<BufferedImage> consumer) {
        decode(buffer.snapshot(), (pts, dts, image) -> {
            consumer.accept(image.get());
            return true;
        });
    }

    /** Buffer'daki tüm frame'leri liste olarak döner. 1080p'de 150 frame ~1 GB eder. */
    public List<BufferedImage> allImages() {
        List<BufferedImage> out = new ArrayList<>();
        forEachImage(out::add);
        return out;
    }

    /** En son frame'i resim olarak döner (sadece son GOP decode edilir). Buffer boşsa null. */
    public BufferedImage latestImage() {
        List<AVPacket> gop = buffer.snapshotLastGop();
        BufferedImage[] last = new BufferedImage[1];
        long[] lastPts = {Long.MIN_VALUE};
        decode(gop, (pts, dts, image) -> {
            if (pts >= lastPts[0]) { lastPts[0] = pts; last[0] = image.get(); }
            return true;
        });
        return last[0];
    }

    private void decode(List<AVPacket> packets, PacketDecoder.FrameHandler handler) {
        try {
            if (packets.isEmpty()) return;
            AVCodecParameters par;
            synchronized (parLock) {
                if (codecPar == null) return;
                par = avcodec_parameters_alloc();
                avcodec_parameters_copy(par, codecPar);
            }
            try (PacketDecoder dec = new PacketDecoder(par)) {
                for (AVPacket p : packets) {
                    if (!dec.decode(p, handler)) return;
                }
                dec.flush(handler);
            } finally {
                avcodec_parameters_free(par);
            }
        } finally {
            PacketRingBuffer.release(packets);
        }
    }

    public long millisToDts(long millis) {
        return millis * timeBaseDen / (1000L * timeBaseNum);
    }

    public long dtsToMillis(long dts) {
        return dts * 1000L * timeBaseNum / timeBaseDen;
    }

    public long oldestDts() { return buffer.oldestDts(); }
    public long newestDts() { return buffer.newestDts(); }
    public int timeBaseNum() { return timeBaseNum; }
    public int timeBaseDen() { return timeBaseDen; }
    public int bufferedFrames() { return buffer.size(); }
    public int capacity() { return buffer.capacity(); }
    public long bufferedBytes() { return buffer.bytes(); }
    public long receivedPackets() { return receivedPackets.get(); }
    public boolean isConnected() { return connected; }
    public double fps() { return fps; }
    public int width() { return width; }
    public int height() { return height; }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        worker.interrupt();
        worker.join(7000);
        buffer.close();
        synchronized (parLock) {
            if (codecPar != null) { avcodec_parameters_free(codecPar); codecPar = null; }
        }
    }
}
