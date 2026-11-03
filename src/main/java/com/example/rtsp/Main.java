package com.example.rtsp;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Kullanım:
 *   mvn -q compile exec:java -Dexec.args="rtsp://kullanici:sifre@192.168.1.10:554/stream 150"
 *
 * Komutlar (konsola yazıp ENTER):
 *   s     → durum bilgisi
 *   l     → en son frame'i JPEG olarak kaydet
 *   a     → buffer'daki tüm frame'leri JPEG olarak kaydet
 *   q     → çıkış
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Kullanım: Main <rtsp-url> [frame-sayısı=150]");
            System.exit(1);
        }
        String url = args[0];
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 150; // 15 fps × 10 sn

        try (RtspPacketBuffer rtsp = new RtspPacketBuffer(url, frames)) {
            rtsp.start();
            System.out.println("Komutlar: s=durum, l=son frame, a=tüm buffer, q=çıkış");

            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null) {
                switch (line.trim()) {
                    case "s" -> printStatus(rtsp);
                    case "l" -> saveLatest(rtsp);
                    case "a" -> saveAll(rtsp);
                    case "q" -> { return; }
                    default -> System.out.println("Komutlar: s, l, a, q");
                }
            }
        }
    }

    private static void printStatus(RtspPacketBuffer r) {
        long rawPerFrame = (long) r.width() * r.height() * 3;
        System.out.printf(
                "Bağlı: %s | %dx%d @ %.1f fps | Buffer: %d/%d frame, %.2f MB (ham olsaydı ~%.0f MB) | Toplam paket: %d%n",
                r.isConnected(), r.width(), r.height(), r.fps(),
                r.bufferedFrames(), r.capacity(), r.bufferedBytes() / 1e6,
                rawPerFrame * r.bufferedFrames() / 1e6, r.receivedPackets());
    }

    private static void saveLatest(RtspPacketBuffer r) throws IOException {
        long t = System.nanoTime();
        BufferedImage img = r.latestImage();
        if (img == null) { System.out.println("Buffer henüz boş."); return; }
        File f = new File("latest_" + stamp() + ".jpg");
        ImageIO.write(img, "jpg", f);
        System.out.printf("Kaydedildi: %s (%d ms)%n", f.getAbsolutePath(), (System.nanoTime() - t) / 1_000_000);
    }

    private static void saveAll(RtspPacketBuffer r) {
        File dir = new File("frames_" + stamp());
        if (!dir.mkdirs()) { System.out.println("Klasör oluşturulamadı: " + dir); return; }
        long t = System.nanoTime();
        int[] i = {0};
        r.forEachImage(img -> {
            try {
                ImageIO.write(img, "jpg", new File(dir, String.format("frame_%04d.jpg", i[0]++)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        System.out.printf("%d frame kaydedildi: %s (%d ms)%n",
                i[0], dir.getAbsolutePath(), (System.nanoTime() - t) / 1_000_000);
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
