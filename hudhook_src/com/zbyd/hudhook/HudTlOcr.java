package com.zbyd.hudhook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traffic-light COUNTDOWN bridge. The adaptive-signal "до зелёного N" is rendered on Yandex's map
 * (not exposed as SDK data in Navi 23.7.3), so we OCR it: capture the app's own window in-process
 * (PixelCopy — no MediaProjection), recognise the 1-2 digit countdown, and publish it as the BYD
 * sdTrafficLight wait-number (via HudSomeIp). OCR uses ML Kit if the host bundles it (reflection);
 * otherwise this stays inert until a digit recogniser is supplied. Only runs when a traffic-light
 * is upcoming (HudEvents sets the flag) to avoid wasted captures.
 */
public final class HudTlOcr {

    private static final Pattern DIGITS = Pattern.compile("\\b([0-9]{1,2})\\b");
    private static volatile boolean sLightAhead;
    private static volatile Activity sActivity;
    private static Handler sBg;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** HudEvents flips this when route.getTrafficLights() has one near. */
    public static void setLightAhead(boolean v) {
        sLightAhead = v;
        if (v && sBg == null) start();
    }

    /** Injected once (e.g. NavigatorActivity.onResume) so we have a window to capture. */
    public static void setActivity(Activity a) {
        HudLog.f("setActivity (onStart hook works, dex loaded)");
        sActivity = a;
        try { HudLocation.install(); } catch (Throwable t) {}   // swap location source early (before subs)
        try { HudMapCapture.start(a); } catch (Throwable t) {}  // live Yandex map -> HUD map panel (0x8003)
        try { HudVoice.attach(a); } catch (Throwable t) {}      // Alice voice -> car control (context for TTS confirm)
    }

    private static void start() {
        try {
            HandlerThread t = new HandlerThread("hud-tl-ocr");
            t.start(); sBg = new Handler(t.getLooper());
            sBg.post(LOOP);
        } catch (Throwable th) {}
    }

    private static final Runnable LOOP = new Runnable() {
        @Override public void run() {
            try { if (sLightAhead) tick(); } catch (Throwable t) {}
            if (sBg != null) sBg.postDelayed(this, 1000L);
        }
    };

    private static void tick() {
        final Activity a = sActivity;
        if (a == null || Build.VERSION.SDK_INT < 24) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    Window w = a.getWindow();
                    View root = w == null ? null : w.getDecorView();
                    if (root == null || root.getWidth() <= 0) return;
                    final Bitmap bmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(),
                        Bitmap.Config.ARGB_8888);
                    PixelCopy.request(w, bmp, new PixelCopy.OnPixelCopyFinishedListener() {
                        @Override public void onPixelCopyFinished(int res) {
                            if (res == PixelCopy.SUCCESS && sBg != null)
                                sBg.post(new Runnable() { @Override public void run() { ocr(a, bmp); } });
                        }
                    }, MAIN);
                } catch (Throwable t) {}
            }
        });
    }

    private static void ocr(Activity a, Bitmap full) {
        try {
            // crop to the lower-centre band where the route + signal badge live (tunable)
            int w = full.getWidth(), h = full.getHeight();
            Rect r = new Rect(w / 4, h / 2, w * 3 / 4, h * 9 / 10);
            Bitmap crop = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height());
            int best = HudDigits.recognize(crop);   // dependency-free digit recogniser (no ML Kit)
            if (best < 0) {                          // fallback: ML Kit text if the host ever bundles it
                String text = mlkitText(crop);
                if (text != null) {
                    Matcher m = DIGITS.matcher(text);
                    while (m.find()) { int v = Integer.parseInt(m.group(1));
                        if (v >= 1 && v <= 99) { best = v; break; } }
                }
            }
            if (best > 0) HudSomeIp.pushLightCountdown(a.getApplicationContext(), best);
        } catch (Throwable t) {}
    }

    /** ML Kit latin text recognition via reflection (synchronous wait). Returns null if unavailable. */
    private static String mlkitText(Bitmap bmp) {
        try {
            Class<?> opts = Class.forName("com.google.mlkit.vision.text.latin.TextRecognizerOptions");
            Object def = opts.getField("DEFAULT_OPTIONS").get(null);
            Class<?> rec = Class.forName("com.google.mlkit.vision.text.TextRecognition");
            Object recognizer = rec.getMethod("getClient",
                Class.forName("com.google.mlkit.vision.interfaces.Detector$DetectorOptions")).invoke(null, def);
            Class<?> img = Class.forName("com.google.mlkit.vision.common.InputImage");
            Object input = img.getMethod("fromBitmap", Bitmap.class, int.class).invoke(null, bmp, 0);
            Object task = recognizer.getClass().getMethod("process", img).invoke(recognizer, input);
            Object result = Class.forName("com.google.android.gms.tasks.Tasks")
                .getMethod("await", Class.forName("com.google.android.gms.tasks.Task")).invoke(null, task);
            Method getText = result.getClass().getMethod("getText");
            Object s = getText.invoke(result);
            return s == null ? null : String.valueOf(s);
        } catch (Throwable t) { return null; }   // ML Kit not bundled → inert
    }
}
