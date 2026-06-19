package com.zbyd.hudhook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * EXPERIMENTAL — deterministic location separation for the AR-HUD-map experiment.
 *
 * MapKit ships a sanctioned injectable location source: MapKit.createDummyLocationManager() ->
 * DummyLocationManager.setLocation(Location, DummyLocationQuality). Yandex wraps it in
 * ru.yandex.yandexmaps.multiplatform.mapkit.location.d (a subclass of ...mapkit.location.f, the source
 * wrapper that MapsLocationManagerImpl.p() returns). A one-line smali patch makes p() return OUR holder
 * when enabled, so ALL of Yandex's positioning reads the dummy we feed — INDEPENDENT of the Android
 * system mock. We then:
 *   - feed the dummy the REAL car position (HudLocationCar) -> Yandex stays real;
 *   - drive the Android system mock to China (HudLocation) -> ONLY launchermap follows it.
 * Net: launchermap=China (can build/navigate a China route -> AR-HUD 0x8003 map subscription engages),
 * Yandex=real (IVI + captured map correct). Gated by HudFlags MOCK_CHINA (same toggle as the system mock).
 *
 * All Yandex/MapKit classes are obfuscated/3rd-party -> pure reflection. Fails closed (holder()==null ->
 * the smali patch falls through to the original real source).
 */
public final class HudDummyLocation {

    private static final String CLS_FACTORY = "com.yandex.mapkit.MapKitFactory";
    private static final String CLS_LOCATION = "com.yandex.mapkit.location.Location";
    private static final String CLS_POINT    = "com.yandex.mapkit.geometry.Point";
    private static final String CLS_QUALITY  = "com.yandex.mapkit.location.DummyLocationQuality";
    private static final String CLS_HOLDER   = "ru.yandex.yandexmaps.multiplatform.mapkit.location.d";  // f-subclass MapsLocationManagerImpl.p() returns

    private static volatile boolean sEnabled;
    private static volatile Object sHolder;        // the `d` wrapper (== an `f`) returned to s.p()
    private static volatile Object sDummyLm;       // com.yandex.mapkit.location.DummyLocationManager
    private static volatile Object sQualityHigh;   // DummyLocationQuality.HIGH
    private static Constructor<?> sLocCtor, sPointCtor;
    private static Method sSetLocation;
    private static boolean sTried;

    /** Called by the patched MapsLocationManagerImpl.p(); non-null -> Yandex uses our fed dummy. */
    public static Object holder() { return sEnabled ? sHolder : null; }

    public static void setEnabled(boolean on) {
        sEnabled = on;
        if (on) ensure();
        HudLog.f("HudDummyLocation enabled=" + on + " holder=" + (sHolder != null));
    }

    /** Build the dummy holder + cache reflection. Idempotent; runs on the app/MapKit thread lazily. */
    private static synchronized void ensure() {
        if (sHolder != null || sTried) return;
        sTried = true;
        try {
            ClassLoader cl = HudDummyLocation.class.getClassLoader();
            Object mapkit = Class.forName(CLS_FACTORY, true, cl).getMethod("getInstance").invoke(null);
            sDummyLm = mapkit.getClass().getMethod("createDummyLocationManager").invoke(mapkit);
            Class<?> holderCls = Class.forName(CLS_HOLDER, true, cl);
            Class<?> dlmCls = sDummyLm.getClass().getInterfaces().length > 0
                    ? Class.forName("com.yandex.mapkit.location.DummyLocationManager", true, cl) : sDummyLm.getClass();
            sHolder = holderCls.getConstructor(dlmCls).newInstance(sDummyLm);
            sSetLocation = dlmCls.getMethod("setLocation",
                    Class.forName(CLS_LOCATION, true, cl), Class.forName(CLS_QUALITY, true, cl));
            // DummyLocationQuality.HIGH
            for (Object q : Class.forName(CLS_QUALITY, true, cl).getEnumConstants())
                if ("HIGH".equals(q.toString())) sQualityHigh = q;
            // Point(double lat, double lon)
            sPointCtor = Class.forName(CLS_POINT, true, cl).getConstructor(double.class, double.class);
            // Location(Point, Double accuracy, Double altitude, Double altitudeAccuracy, Double heading, Double speed, String, long, long)
            sLocCtor = Class.forName(CLS_LOCATION, true, cl).getConstructor(
                    Class.forName(CLS_POINT, true, cl), Double.class, Double.class, Double.class,
                    Double.class, Double.class, String.class, long.class, long.class);
            HudLog.f("HudDummyLocation ready (dummy+holder+setLocation reflected)");
        } catch (Throwable t) { HudLog.f("HudDummyLocation ensure FAIL: " + t); }
    }

    /** Feed the REAL car position into the dummy -> Yandex reads it. Called from HudLocationCar/HudLocation. */
    public static void feed(double lat, double lon, double altM, float accM, float bearingDeg, float speedMs) {
        if (!sEnabled || sSetLocation == null || sDummyLm == null) return;
        try {
            Object point = sPointCtor.newInstance(lat, lon);
            Object loc = sLocCtor.newInstance(point, (double) accM, altM, 10.0,
                    (double) bearingDeg, (double) speedMs, null,
                    System.currentTimeMillis(), android.os.SystemClock.elapsedRealtimeNanos());
            sSetLocation.invoke(sDummyLm, loc, sQualityHigh);
        } catch (Throwable t) { if (sFeedLog++ < 3) HudLog.f("HudDummyLocation feed FAIL: " + t); }
    }
    private static int sFeedLog;
}
