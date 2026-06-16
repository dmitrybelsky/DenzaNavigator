package com.zbyd.hudhook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Dependency-free 1-2 digit recogniser for the Yandex traffic-light countdown badge (ML Kit text is
 * not bundled in Navi). Templates for 0-9 are rendered at runtime with the device font (≈ Yandex's),
 * each reduced to a GxG binary grid; an input crop is binarised, split into digit blobs by column
 * projection, and each blob matched to the nearest template. Returns the integer (1-99) or -1.
 */
public final class HudDigits {

    private static final int G = 8;            // feature grid GxG
    private static int[][] TPL;                // [10][G*G] template signatures

    private static synchronized void buildTemplates() {
        if (TPL != null) return;
        int[][] t = new int[10][];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setColor(Color.WHITE); p.setTextSize(48);
        for (int d = 0; d <= 9; d++) {
            Bitmap b = Bitmap.createBitmap(40, 56, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b); c.drawColor(Color.BLACK);
            c.drawText(String.valueOf(d), 6, 46, p);
            t[d] = featGrid(b, true);
            b.recycle();
        }
        TPL = t;
    }

    /** crop = the countdown badge region. Bright digits assumed; tries both polarities. */
    public static int recognize(Bitmap crop) {
        if (crop == null) return -1;
        buildTemplates();
        int best = recognizePolarity(crop, true);
        if (best < 0) best = recognizePolarity(crop, false);
        return best;
    }

    private static int recognizePolarity(Bitmap crop, boolean bright) {
        boolean[][] bin = binarize(crop, bright);
        int w = bin.length, h = bin[0].length;
        // column ink projection → digit column ranges (≤ 2)
        int[] col = new int[w];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) if (bin[x][y]) col[x]++;
        int thr = 1;
        java.util.ArrayList<int[]> runs = new java.util.ArrayList<>();
        int s = -1;
        for (int x = 0; x < w; x++) {
            if (col[x] >= thr && s < 0) s = x;
            else if (col[x] < thr && s >= 0) { runs.add(new int[]{s, x}); s = -1; }
        }
        if (s >= 0) runs.add(new int[]{s, w});
        if (runs.isEmpty() || runs.size() > 2) return -1;
        StringBuilder num = new StringBuilder();
        for (int[] rn : runs) {
            int d = classifyBlob(bin, rn[0], rn[1], h);
            if (d < 0) return -1;
            num.append(d);
        }
        try { int v = Integer.parseInt(num.toString()); return (v >= 1 && v <= 99) ? v : -1; }
        catch (Throwable t) { return -1; }
    }

    private static int classifyBlob(boolean[][] bin, int x0, int x1, int h) {
        // tight vertical bounds
        int y0 = h, y1 = 0;
        for (int x = x0; x < x1; x++) for (int y = 0; y < h; y++)
            if (bin[x][y]) { if (y < y0) y0 = y; if (y > y1) y1 = y; }
        if (y1 <= y0 || (x1 - x0) < 3 || (y1 - y0) < 6) return -1;
        int[] f = featRegion(bin, x0, x1, y0, y1 + 1);
        int bestD = -1; long bestErr = Long.MAX_VALUE;
        for (int d = 0; d <= 9; d++) {
            long e = 0;
            for (int i = 0; i < f.length; i++) { int dd = f[i] - TPL[d][i]; e += (long) dd * dd; }
            if (e < bestErr) { bestErr = e; bestD = d; }
        }
        return bestD;
    }

    private static boolean[][] binarize(Bitmap b, boolean bright) {
        int w = b.getWidth(), h = b.getHeight();
        boolean[][] o = new boolean[w][h];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
            int px = b.getPixel(x, y);
            int lum = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3;
            o[x][y] = bright ? lum > 170 : lum < 90;
        }
        return o;
    }

    /** GxG binary density features over a bitmap (rendered template). */
    private static int[] featGrid(Bitmap b, boolean bright) {
        boolean[][] bin = binarize(b, bright);
        return featRegion(bin, 0, bin.length, 0, bin[0].length);
    }

    /** GxG cell ink-density (0..255) over a sub-region of a binary map. */
    private static int[] featRegion(boolean[][] bin, int x0, int x1, int y0, int y1) {
        int[] f = new int[G * G];
        int rw = x1 - x0, rh = y1 - y0;
        for (int gx = 0; gx < G; gx++) for (int gy = 0; gy < G; gy++) {
            int cx0 = x0 + gx * rw / G, cx1 = x0 + (gx + 1) * rw / G;
            int cy0 = y0 + gy * rh / G, cy1 = y0 + (gy + 1) * rh / G;
            int ink = 0, tot = 0;
            for (int x = cx0; x < cx1; x++) for (int y = cy0; y < cy1; y++) { tot++; if (bin[x][y]) ink++; }
            f[gy * G + gx] = tot == 0 ? 0 : (ink * 255 / tot);
        }
        return f;
    }
}
