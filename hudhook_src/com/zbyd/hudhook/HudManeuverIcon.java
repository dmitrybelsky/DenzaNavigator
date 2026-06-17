package com.zbyd.hudhook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a maneuver-arrow PNG per BYD turn-id for the windshield HUD f8 field (HUD_ROAD_INFO).
 * Pure Canvas (no resources) so it lives in the in-process mod dex. Cached per turn-id — each
 * glyph is built once. White fill + dark outline so it reads on any HUD projector tint.
 *
 * BYD turn-id (see HudEvents.bydIcon): 2=left 3=right 4=slightL 5=slightR 6=sharpL 7=sharpR
 * 8=uturn 9=straight 11/17/18=roundabout 15=finish.
 */
public final class HudManeuverIcon {

    private static final int SZ = 100;          // icon canvas (px)
    private static final Map<Integer, byte[]> CACHE = new HashMap<>();

    /** PNG bytes of the arrow for a BYD turn-id (cached). */
    public static synchronized byte[] png(int turnId) {
        byte[] c = CACHE.get(turnId);
        if (c != null) return c;
        byte[] b = render(turnId);
        CACHE.put(turnId, b);
        return b;
    }

    private static byte[] render(int turnId) {
        Bitmap bmp = Bitmap.createBitmap(SZ, SZ, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL_AND_STROKE);
        fill.setColor(Color.WHITE);
        fill.setStrokeWidth(14f);
        fill.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeJoin(Paint.Join.ROUND);
        Paint outline = new Paint(fill);
        outline.setColor(0xFF103040);           // dark teal outline for contrast
        outline.setStrokeWidth(22f);

        switch (turnId) {
            case 15: finish(cv); return toPng(bmp);             // destination flag
            case 11: case 17: case 18:
                roundabout(cv, fill, outline, turnId); return toPng(bmp);
            default:
                arrow(cv, fill, outline, turnId); return toPng(bmp);
        }
    }

    // Bent stem + arrowhead. Each turn-id maps to a polyline (stem up, then bend) in 100x100 space.
    private static void arrow(Canvas cv, Paint fill, Paint outline, int turnId) {
        float[][] pts = stem(turnId);           // [{x,y}...] from tail to tip
        Path p = new Path();
        p.moveTo(pts[0][0], pts[0][1]);
        for (int i = 1; i < pts.length; i++) p.lineTo(pts[i][0], pts[i][1]);
        cv.drawPath(p, outline);
        cv.drawPath(p, fill);
        // arrowhead at the tip, oriented along the last segment
        float[] a = pts[pts.length - 2], b = pts[pts.length - 1];
        head(cv, outline, b[0], b[1], b[0] - a[0], b[1] - a[1], 26f);
        head(cv, fill, b[0], b[1], b[0] - a[0], b[1] - a[1], 18f);
    }

    private static float[][] stem(int turnId) {
        switch (turnId) {
            case 2: return new float[][]{{64,86},{64,50},{24,50}};            // left 90
            case 3: return new float[][]{{36,86},{36,50},{76,50}};            // right 90
            case 4: return new float[][]{{60,86},{56,52},{26,28}};            // slight left
            case 5: return new float[][]{{40,86},{44,52},{74,28}};            // slight right
            case 6: return new float[][]{{66,80},{60,44},{26,66}};            // sharp left
            case 7: return new float[][]{{34,80},{40,44},{74,66}};            // sharp right
            case 8: return new float[][]{{74,86},{74,46},{40,46},{40,68}};    // u-turn (down-left return)
            case 9: default: return new float[][]{{50,88},{50,22}};           // straight
        }
    }

    // Filled triangular arrowhead at (x,y) pointing along (dx,dy).
    private static void head(Canvas cv, Paint paint, float x, float y, float dx, float dy, float len) {
        double a = Math.atan2(dy, dx);
        Path t = new Path();
        t.moveTo(x + (float) (Math.cos(a) * len), y + (float) (Math.sin(a) * len));
        double l = a + Math.toRadians(140), r = a - Math.toRadians(140);
        t.lineTo(x + (float) (Math.cos(l) * len), y + (float) (Math.sin(l) * len));
        t.lineTo(x + (float) (Math.cos(r) * len), y + (float) (Math.sin(r) * len));
        t.close();
        Paint f = new Paint(paint); f.setStyle(Paint.Style.FILL); f.setStrokeWidth(0);
        cv.drawPath(t, f);
    }

    private static void roundabout(Canvas cv, Paint fill, Paint outline, int turnId) {
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE); ring.setColor(Color.WHITE); ring.setStrokeWidth(12f);
        Paint ringO = new Paint(ring); ringO.setColor(0xFF103040); ringO.setStrokeWidth(20f);
        cv.drawCircle(50, 56, 26, ringO); cv.drawCircle(50, 56, 26, ring);
        // exit stub: left(17) / right(18) / straight(11)
        float ex = turnId == 17 ? 18 : (turnId == 18 ? 82 : 50);
        float ey = turnId == 11 ? 14 : 30;
        Path p = new Path(); p.moveTo(50, 30); p.lineTo(ex, ey);
        cv.drawPath(p, outline); cv.drawPath(p, fill);
        head(cv, fill, ex, ey, ex - 50, ey - 30, 16f);
    }

    private static void finish(Canvas cv) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(Color.WHITE);
        float x0 = 30, y0 = 24, c = 10; int n = 4;
        cv.drawRect(x0 - 3, y0 - 3, x0 + c * n + 3, y0 + c * n + 3, bg);
        Paint blk = new Paint(Paint.ANTI_ALIAS_FLAG); blk.setColor(0xFF103040);
        for (int r = 0; r < n; r++)
            for (int col = 0; col < n; col++)
                if (((r + col) & 1) == 0)
                    cv.drawRect(x0 + col * c, y0 + r * c, x0 + col * c + c, y0 + r * c + c, blk);
        Paint pole = new Paint(blk); pole.setStrokeWidth(8f);
        cv.drawLine(x0 - 3, y0, x0 - 3, 84, pole);
    }

    private static byte[] toPng(Bitmap bmp) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        bmp.recycle();
        return o.toByteArray();
    }
}
