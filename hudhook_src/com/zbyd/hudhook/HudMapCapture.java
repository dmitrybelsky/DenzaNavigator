package com.zbyd.hudhook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.io.ByteArrayOutputStream;

/**
 * Captures the live Yandex map (the whole window — PixelCopy's Window variant reads the GL-composited
 * SurfaceView the MapKit map renders into, which View.draw() would miss) and pushes it to the BYD HUD
 * map panel via SOME/IP event 0x8003 (HudNavigationmap). ~1 fps, downscaled to keep the binder payload
 * small. Only runs while NaviKit guidance is active.
 */
public final class HudMapCapture {

    private static final int INTERVAL_MS = 1000;
    private static final int MAX_W = 480;          // HUD panel width / binder-limit guard

    private static volatile boolean sRunning;
    private static volatile Activity sAct;
    private static Handler sBg;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void start(Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 26) return;
        sAct = a;
        if (sRunning) return;
        sRunning = true;
        HandlerThread t = new HandlerThread("hud-map"); t.start();
        sBg = new Handler(t.getLooper());
        MAIN.postDelayed(TICK, INTERVAL_MS);
        HudLog.f("HudMapCapture started");
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            try { capture(); } catch (Throwable t) {}
            MAIN.postDelayed(this, INTERVAL_MS);
        }
    };

    private static void capture() {
        final Activity a = sAct;
        if (a == null || !HudEvents.guidanceActive()) return;   // only push map while navigating
        final Window w = a.getWindow();
        final View dv = w == null ? null : w.peekDecorView();
        if (dv == null || dv.getWidth() <= 0 || dv.getHeight() <= 0) return;
        final Bitmap full = Bitmap.createBitmap(dv.getWidth(), dv.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(w, full, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int res) {
                    if (res != PixelCopy.SUCCESS) { full.recycle(); return; }
                    sBg.post(new Runnable() { @Override public void run() { sendScaled(full); } });
                }
            }, MAIN);
        } catch (Throwable t) { full.recycle(); }
    }

    private static void sendScaled(Bitmap full) {
        try {
            int w = full.getWidth(), h = full.getHeight();
            Bitmap scaled = full;
            if (w > MAX_W) scaled = Bitmap.createScaledBitmap(full, MAX_W, h * MAX_W / w, true);
            overlayEv(scaled);                                  // SOC/range badge on the map frame
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 90, o);
            if (scaled != full) scaled.recycle();
            full.recycle();
            byte[] png = o.toByteArray();
            HudSomeIp.pushMap(sAct, png);              // windshield HUD map panel (0x8003)
            HudClusterClient.send(png);                // instrument-cluster nav area (masquerade app)
        } catch (Throwable t) {}
    }

    /** Draw an EV SOC/range badge in the map frame corner (the map panel is free canvas — the HUD
     *  struct has no battery field). Mutable scaled bitmap only; skipped if EV data not yet available. */
    private static void overlayEv(Bitmap bmp) {
        try {
            int soc = HudCarClient.soc(); int range = (int) HudCarClient.rangeKm();
            if (soc < 0 || !bmp.isMutable()) return;
            boolean tireLow = false; for (int p : HudCarClient.tpms()) if (p > 50 && p < 180) tireLow = true;
            String txt = soc + "%  " + range + "km" + (tireLow ? "  !шина" : "");
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xC0000000);
            float th = bmp.getHeight() * 0.09f, pad = th * 0.3f;
            cv.drawRect(0, 0, th * 5.2f, th + pad, bg);
            android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFF00E676); tp.setTextSize(th); tp.setFakeBoldText(true);
            cv.drawText(txt, pad, th, tp);
        } catch (Throwable t) {}
    }
}
