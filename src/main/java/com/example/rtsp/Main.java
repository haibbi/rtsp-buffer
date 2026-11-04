package com.example.rtsp;

import java.io.File;

/**
 * Kullanım:
 *   mvn -q compile exec:java -Dexec.args="rtsp://kullanici:sifre@192.168.1.10:554/stream 150 captures"
 *
 * Argümanlar: <rtsp-url> [buffer-frame-sayısı=150] [çıktı-klasörü=captures]
 * Durdurmak için ENTER.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Kullanım: Main <rtsp-url> [buffer-frame-sayısı=150] [çıktı-klasörü=captures]");
            System.exit(1);
        }
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 150; // 15 fps × 10 sn
        File out = new File(args.length > 2 ? args[2] : "captures");

        try (RtspEventCapture capture = new RtspEventCapture(args[0], frames, out)) {
            capture.start();
            System.out.println("Dinleniyor, kayıtlar: " + out.getAbsolutePath() + " (çıkmak için ENTER)");
            System.in.read();
        }
    }
}
