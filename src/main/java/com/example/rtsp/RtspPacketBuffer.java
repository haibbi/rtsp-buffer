package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

/**
 * RTSP akışını arka planda dinler, video paketlerini decode etmeden RAM'de tutar.
 * Resim istendiğinde paketleri decode eder.
 *
 * Kopma olursa otomatik yeniden bağlanır.
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
                g.setOption("rtsp_transport", "tcp");   // UDP'de paket kaybı olur
                g.setOption("timeout", "5000000");      // 5 sn (mikrosaniye) okuma timeout'u
                g.setOption("fflags", "nobuffer");
                g.start();

                int videoStream = g.getVideoStream();
                if (videoStream < 0) throw new IllegalStateException("Akışta video yok");

                synchronized (parLock) {
                    if (codecPar != null) avcodec_parameters_free(codecPar);
                    codecPar = avcodec_parameters_alloc();
                    avcodec_parameters_copy(codecPar,
                            g.getFormatContext().streams(videoStream).codecpar());
                }
                buffer.clear(); // eski bağlantının paketleri yeni decoder'la uyumsuz olabilir

                fps = g.getFrameRate();
                width = g.getImageWidth();
                height = g.getImageHeight();
                connected = true;
                System.out.printf("Bağlandı: %dx%d, %.1f fps, codec=%s%n",
                        width, height, fps, g.getVideoCodecName());

                AVPacket pkt;
                while (running && (pkt = g.grabPacket()) != null) {
                    if (pkt.stream_index() == videoStream) {
                        buffer.add(pkt);  // içeride klonlanır
                        receivedPackets.incrementAndGet();
                    }
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

    /** Buffer'daki tüm frame'leri eski→yeni sırayla resim olarak döner. */
    public List<BufferedImage> allImages() {
        List<BufferedImage> out = new ArrayList<>();
        decode(buffer.snapshot(), out::add);
        return out;
    }

    /**
     * Buffer'daki frame'leri tek tek işler; hepsini aynı anda RAM'e almaz.
     * 1080p'de 150 frame'i listeye almak ~1 GB eder, bu yöntem tercih edilmeli.
     */
    public void forEachImage(java.util.function.Consumer<BufferedImage> consumer) {
        decode(buffer.snapshot(), consumer);
    }

    /** En son frame'i resim olarak döner (sadece son GOP decode edilir). Buffer boşsa null. */
    public BufferedImage latestImage() {
        BufferedImage[] last = new BufferedImage[1];
        decode(buffer.snapshotLastGop(), img -> last[0] = img);
        return last[0];
    }

    private void decode(List<AVPacket> packets, java.util.function.Consumer<BufferedImage> out) {
        try {
            if (packets.isEmpty()) return;
            AVCodecParameters par;
            synchronized (parLock) {
                if (codecPar == null) return;
                par = avcodec_parameters_alloc();
                avcodec_parameters_copy(par, codecPar);
            }
            try (PacketDecoder dec = new PacketDecoder(par)) {
                for (AVPacket p : packets) dec.decode(p, out);
                dec.flush(out);
            } finally {
                avcodec_parameters_free(par);
            }
        } finally {
            PacketRingBuffer.release(packets);
        }
    }

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
