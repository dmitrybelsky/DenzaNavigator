package com.zbyd.hudhook;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.lang.reflect.Method;

/**
 * REVERSE channel: feed a fused CAR position (RTK/INS/CAN dead-reckoning) into Yandex by overriding the
 * ANDROID system location. MapKit's setLocationManager is native (rejects a Java proxy: needs a
 * nativeObject field) and ExternalLocationReceiver.send() is ignored while GPS has a fix — but MapKit's
 * own native LocationManager READS the android system providers, so the robust, MapKit-internals-agnostic
 * path is system mock location (proven in this project for AMap). We addTestProvider(gps/fused) and push
 * setTestProviderLocation at ~5 Hz. Requires the mock-location appop, granted non-root over adb:
 *   adb shell appops set ru.yandex.yandexnavi android:mock_location allow
 * On the car the same grant + this feed makes Yandex follow the vehicle RTK/INS instead of phone GPS.
 */
public final class HudLocation {

    private static final String[] PROVIDERS = { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused" };
    private static volatile boolean sInstalled;
    private static LocationManager sLm;
    private static Handler sBg;

    private static volatile double sLat, sLon, sAlt = 150.0;
    private static volatile float sAcc = 5f, sBearing = 0f, sSpeed = 0f;
    private static volatile float sVAcc = 10f, sBearAcc = 0f, sSpeedAcc = 0f;  // accuracies (0 = unset)
    private static volatile boolean sHave, sSelfTest;
    private static int sTick;
    private static double sTestLat, sTestLon;

    // EXPERIMENTAL location-separation test (AR-HUD map). When on, we push a CHINA fix to the system test
    // providers. launchermap (fused-only) follows it -> can build/navigate a China route -> AR-HUD 0x8003
    // map subscription engages. Yandex MapKit runs a raw-GNSS spoofing detector (libmaps-mobile.so:
    // location_spoofing_processor_reject_platform_lbs_near_spoofed_gps) so it REJECTS this contradicting
    // mock and stays on the real raw-GNSS position. Net: launchermap=China, Yandex=real -> separation.
    private static volatile boolean sMockChina;
    private static final double BEIJING_LAT = 39.90840, BEIJING_LON = 116.40739;  // Tiananmen (AMap-covered)
    public static void setMockChina(boolean v) { sMockChina = v; HudLog.f("HudLocation mockChina=" + v); }

    private static Context ctx() {
        try { return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); }
        catch (Throwable t) { return null; }
    }

    /** Register mock test providers (idempotent). Needs android:mock_location appop (adb-granted). */
    public static synchronized void install() {
        if (sInstalled) return;
        Context c = ctx(); if (c == null) return;
        try {
            sLm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            int ok = 0;
            for (String p : PROVIDERS) {
                try { addProvider(sLm, p); sLm.setTestProviderEnabled(p, true); ok++; }
                catch (Throwable t) { HudLog.f("addTestProvider " + p + " fail: " + t); }
            }
            if (ok == 0) { HudLog.f("HudLocation: no mock provider (grant appop: adb appops set ru.yandex.yandexnavi android:mock_location allow)"); return; }
            sInstalled = true; startFeed();
            HudLog.f("HudLocation: mock providers installed (" + ok + ")");
        } catch (Throwable t) { HudLog.f("HudLocation install fail: " + t); }
    }

    /** addTestProvider across API levels (legacy 10-arg, else ProviderProperties). */
    private static void addProvider(LocationManager lm, String p) throws Exception {
        try { lm.removeTestProvider(p); } catch (Throwable ignore) {}
        try {
            Method m = LocationManager.class.getMethod("addTestProvider", String.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class,
                boolean.class, boolean.class, int.class, int.class);
            m.invoke(lm, p, false, false, false, false, true, true, true, 1 /*POWER_LOW*/, 1 /*ACCURACY_FINE*/);
            return;
        } catch (NoSuchMethodException e) { /* API 31+ : use ProviderProperties */ }
        Class<?> pp = Class.forName("android.location.provider.ProviderProperties");
        Class<?> bld = Class.forName("android.location.provider.ProviderProperties$Builder");
        Object props = bld.getMethod("build").invoke(bld.getConstructor().newInstance());
        LocationManager.class.getMethod("addTestProvider", String.class, pp).invoke(lm, p, props);
    }

    /** Simple update (legacy callers). */
    public static void push(double lat, double lon, Float accM, Float bearingDeg, Float speedMs) {
        sLat = lat; sLon = lon; sHave = true;
        if (accM != null) sAcc = accM; if (bearingDeg != null) sBearing = bearingDeg; if (speedMs != null) sSpeed = speedMs;
    }

    /** Rich update with the car's real altitude + per-quantity accuracies (RTK/INS-derived). null = keep. */
    public static void pushRich(double lat, double lon, Double altM, Float accM, Float vAccM,
                                Float bearingDeg, Float bearAccDeg, Float speedMs, Float speedAccMs) {
        sLat = lat; sLon = lon; sHave = true;
        if (altM != null)      sAlt = altM;
        if (accM != null)      sAcc = accM;
        if (vAccM != null)     sVAcc = vAccM;
        if (bearingDeg != null) sBearing = bearingDeg;
        if (bearAccDeg != null) sBearAcc = bearAccDeg;
        if (speedMs != null)   sSpeed = speedMs;
        if (speedAccMs != null) sSpeedAcc = speedAccMs;
    }

    private static void pushNow() {
        if (sLm == null) return;
        boolean china = sMockChina;
        if (!sHave && !china) return;
        double lat = china ? BEIJING_LAT : sLat, lon = china ? BEIJING_LON : sLon;   // China override wins over the car feed
        for (String p : PROVIDERS) {
            try {
                Location l = new Location(p);
                l.setLatitude(lat); l.setLongitude(lon); l.setAltitude(sAlt);
                l.setAccuracy(china ? 4f : sAcc); l.setBearing(sBearing); l.setSpeed(china ? 0f : sSpeed);
                // per-quantity accuracies (API 26+) so MapKit weights our high-precision fix correctly
                try { if (sVAcc > 0)     l.setVerticalAccuracyMeters(sVAcc); } catch (Throwable t) {}
                try { if (sBearAcc > 0)  l.setBearingAccuracyDegrees(sBearAcc); } catch (Throwable t) {}
                try { if (sSpeedAcc > 0) l.setSpeedAccuracyMetersPerSecond(sSpeedAcc); } catch (Throwable t) {}
                l.setTime(System.currentTimeMillis());
                l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                sLm.setTestProviderLocation(p, l);
            } catch (Throwable t) {}
        }
    }

    private static void startFeed() {
        if (sBg == null) { HandlerThread t = new HandlerThread("hud-loc"); t.start(); sBg = new Handler(t.getLooper()); }
        sBg.post(FEED);
    }
    private static final Runnable FEED = new Runnable() {
        @Override public void run() {
            if (sMockChina) { sLat = BEIJING_LAT; sLon = BEIJING_LON; sAlt = 50.0; sAcc = 4f; sBearing = 0f; sSpeed = 0f; sHave = true;
                              if (sTick++ % 25 == 0) HudLog.f("mockChina feed -> Beijing (launchermap follows; Yandex should reject as spoof)"); }
            else if (sSelfTest) { sLat = sTestLat + (sTick++ * 0.00001); sLon = sTestLon; sBearing = 0f; sSpeed = 8f; sHave = true;
                             if (sTick % 25 == 0) HudLog.f("selfTest tick=" + sTick + " feed lat=" + sLat); }
            pushNow();
            if (sBg != null) sBg.postDelayed(this, 200L);
        }
    };

    public static void startSelfTest(double lat, double lon) {
        sTestLat = lat; sTestLon = lon; sSelfTest = true; push(lat, lon, 3f, 0f, 8f);
        HudLog.f("selfTest start @ " + lat + "," + lon);
        install();
    }
}
