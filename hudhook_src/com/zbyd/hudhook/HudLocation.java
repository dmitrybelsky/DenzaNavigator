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

    private static volatile double sLat, sLon;
    private static volatile float sAcc = 5f, sBearing = 0f, sSpeed = 0f;
    private static volatile boolean sHave, sSelfTest;
    private static int sTick;
    private static double sTestLat, sTestLon;

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

    /** Update the fused fix delivered to MapKit via the mock providers. */
    public static void push(double lat, double lon, Float accM, Float bearingDeg, Float speedMs) {
        sLat = lat; sLon = lon; sHave = true;
        if (accM != null) sAcc = accM; if (bearingDeg != null) sBearing = bearingDeg; if (speedMs != null) sSpeed = speedMs;
    }

    private static void pushNow() {
        if (!sHave || sLm == null) return;
        for (String p : PROVIDERS) {
            try {
                Location l = new Location(p);
                l.setLatitude(sLat); l.setLongitude(sLon); l.setAccuracy(sAcc);
                l.setBearing(sBearing); l.setSpeed(sSpeed); l.setAltitude(150.0);
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
            if (sSelfTest) { sLat = sTestLat + (sTick++ * 0.00001); sLon = sTestLon; sBearing = 0f; sSpeed = 8f; sHave = true;
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
