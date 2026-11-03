package com.example.rtsp;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.function.Supplier;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Sıkıştırılmış video paketlerini (H.264/H.265 vb.) BufferedImage'a çevirir.
 * Paketler bir keyframe'den başlayarak sırayla verilmelidir.
 * Thread-safe değildir; her decode işlemi için ayrı örnek kullan.
 */
public final class PacketDecoder implements AutoCloseable {

    /** Decoder'dan çıkan her frame için çağrılır. */
    @FunctionalInterface
    public interface FrameHandler {
        /**
         * @param pts   frame'in PTS'i (gösterim zamanı)
         * @param dts   frame'in geldiği paketin DTS'i
         * @param image resmi üretir; sadece bu çağrı sırasında geçerlidir.
         *              Çağrılmazsa BGR dönüşümü hiç yapılmaz (ucuz atlama).
         * @return decode'a devam edilsin mi; false dönerse durulur
         */
        boolean onFrame(long pts, long dts, Supplier<BufferedImage> image);
    }

    private final AVCodecContext ctx;
    private final AVFrame frame = av_frame_alloc();
    private final AVFrame rgb = av_frame_alloc();

    private SwsContext sws;
    private BytePointer rgbBuf;
    private int width = -1, height = -1, srcFormat = -1;

    public PacketDecoder(AVCodecParameters par) {
        AVCodec codec = avcodec_find_decoder(par.codec_id());
        if (codec == null) {
            throw new IllegalStateException("Decoder bulunamadı, codec_id=" + par.codec_id());
        }
        ctx = avcodec_alloc_context3(codec);
        check(avcodec_parameters_to_context(ctx, par), "avcodec_parameters_to_context");
        check(avcodec_open2(ctx, codec, (AVDictionary) null), "avcodec_open2");
    }

    /**
     * Paketi decoder'a verir; çıkan her frame için handler çağrılır (0, 1 veya daha fazla kez).
     *
     * @return handler durmak istediyse false
     */
    public boolean decode(AVPacket pkt, FrameHandler handler) {
        int r = avcodec_send_packet(ctx, pkt);
        if (r < 0 && r != AVERROR_EAGAIN() && r != AVERROR_EOF()) {
            return true; // bozuk paketi atla
        }
        while (true) {
            r = avcodec_receive_frame(ctx, frame);
            if (r < 0) return true; // EAGAIN: daha fazla paket lazım, EOF: bitti
            try {
                long pts = frame.best_effort_timestamp();
                long dts = frame.pkt_dts();
                if (!handler.onFrame(pts, dts, () -> toImage(frame))) return false;
            } finally {
                av_frame_unref(frame);
            }
        }
    }

    /** Decoder'ın içinde bekleyen son frame'leri de çıkarır. Son paketten sonra çağır. */
    public boolean flush(FrameHandler handler) {
        return decode(null, handler);
    }

    private BufferedImage toImage(AVFrame f) {
        int w = f.width(), h = f.height(), fmt = f.format();

        if (sws == null || w != width || h != height || fmt != srcFormat) {
            freeScaler();
            width = w; height = h; srcFormat = fmt;
            sws = sws_getContext(w, h, fmt, w, h, AV_PIX_FMT_BGR24,
                    SWS_BILINEAR, null, null, (DoublePointer) null);
            if (sws == null) throw new IllegalStateException("sws_getContext başarısız");

            int size = av_image_get_buffer_size(AV_PIX_FMT_BGR24, w, h, 1);
            rgbBuf = new BytePointer(av_malloc(size)).capacity(size);
            av_image_fill_arrays(rgb.data(), rgb.linesize(), rgbBuf, AV_PIX_FMT_BGR24, w, h, 1);
        }

        sws_scale(sws, f.data(), f.linesize(), 0, h, rgb.data(), rgb.linesize());

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        rgbBuf.position(0).get(dst);
        return img;
    }

    private void freeScaler() {
        if (sws != null) { sws_freeContext(sws); sws = null; }
        if (rgbBuf != null) { av_free(rgbBuf); rgbBuf = null; }
    }

    private static void check(int r, String op) {
        if (r < 0) throw new IllegalStateException(op + " başarısız: " + r);
    }

    @Override
    public void close() {
        freeScaler();
        av_frame_free(frame);
        av_frame_free(rgb);
        avcodec_free_context(ctx);
    }
}
