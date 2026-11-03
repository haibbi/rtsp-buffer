package com.example.rtsp;

import java.awt.image.BufferedImage;

/**
 * DTS sorgusunun sonucu.
 *
 * @param image        decode edilmiş frame
 * @param dts          seçilen frame'in gerçek DTS'i (istenenle aynı olmayabilir)
 * @param pts          seçilen frame'in PTS'i
 * @param requestedDts sorguda verilen DTS
 * @param timeBaseNum  zaman tabanı payı (genelde 1)
 * @param timeBaseDen  zaman tabanı paydası (RTSP'de genelde 90000)
 */
public record TimedImage(BufferedImage image, long dts, long pts, long requestedDts,
                         int timeBaseNum, int timeBaseDen) {

    /** İstenen DTS ile seçilen DTS arasındaki fark (DTS biriminde, işaretli). */
    public long deltaDts() {
        return dts - requestedDts;
    }

    /** Aynı farkın milisaniye karşılığı. */
    public double deltaMillis() {
        return deltaDts() * 1000.0 * timeBaseNum / timeBaseDen;
    }

    public boolean exactMatch() {
        return dts == requestedDts;
    }
}
