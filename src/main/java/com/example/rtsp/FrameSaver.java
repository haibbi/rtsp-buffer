package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.opencv.opencv_core.Mat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;

/**
 * GOP'u ayrı bir thread'de decode eder, sadece hedef frame'i BGR Mat'e çevirip imwrite ile yazar.
 * Okuma thread'ini bloklamaz.
 */
final class FrameSaver implements AutoCloseable {

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "frame-saver");
        t.setDaemon(true);
        return t;
    });

    /** par ve gop'un sahipliğini alır; iş bitince serbest bırakılır. */
    void submit(AVCodecParameters par, VideoBuffer.Gop gop, String file) {
        exec.execute(() -> {
            try {
                if (!decodeAndSave(par, gop, file)) System.err.println("Frame decode edilemedi: " + file);
            } catch (Exception e) {
                System.err.println("Kaydetme hatası (" + file + "): " + e.getMessage());
            } finally {
                avcodec_parameters_free(par);
                VideoBuffer.free(gop.packets());
            }
        });
    }

    private static boolean decodeAndSave(AVCodecParameters par, VideoBuffer.Gop gop, String file) {
        AVCodec codec = avcodec_find_decoder(par.codec_id());
        AVCodecContext ctx = avcodec_alloc_context3(codec);
        AVFrame frame = av_frame_alloc();
        try {
            if (avcodec_parameters_to_context(ctx, par) < 0
                    || avcodec_open2(ctx, codec, (AVDictionary) null) < 0) return false;

            for (AVPacket p : gop.packets()) {
                if (sendAndSave(ctx, frame, p, gop, file)) return true;
            }
            return sendAndSave(ctx, frame, null, gop, file); // decoder'da bekleyenler
        } finally {
            av_frame_free(frame);
            avcodec_free_context(ctx);
        }
    }

    private static boolean sendAndSave(AVCodecContext ctx, AVFrame f, AVPacket p,
                                       VideoBuffer.Gop gop, String file) {
        avcodec_send_packet(ctx, p);
        while (avcodec_receive_frame(ctx, f) >= 0) {
            // PTS frame'e kendi paketinden geçer; B-frame sıralaması olsa da doğru frame'i bulur
            boolean hit = gop.targetPts() != AV_NOPTS_VALUE
                    ? f.pts() == gop.targetPts()
                    : f.pkt_dts() == gop.targetDts();
            if (hit) write(f, file);
            av_frame_unref(f);
            if (hit) return true;
        }
        return false;
    }

    /** YUV frame'i doğrudan Mat'in belleğine BGR olarak dönüştürür; ara kopya yok. */
    private static void write(AVFrame f, String file) {
        int w = f.width(), h = f.height();
        SwsContext sws = sws_getContext(w, h, f.format(), w, h, AV_PIX_FMT_BGR24,
                SWS_BILINEAR, null, null, (DoublePointer) null);
        try (Mat mat = new Mat(h, w, CV_8UC3);
             PointerPointer<?> dst = new PointerPointer<>(mat.data());
             IntPointer stride = new IntPointer(1).put((int) mat.step())) {
            sws_scale(sws, f.data(), f.linesize(), 0, h, dst, stride);
            if (!imwrite(file, mat)) throw new IllegalStateException("imwrite başarısız");
        } finally {
            sws_freeContext(sws);
        }
    }

    @Override
    public void close() throws InterruptedException {
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
    }
}
