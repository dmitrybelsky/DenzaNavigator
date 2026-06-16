package com.zbyd.hudhook;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.lang.reflect.Method;

/**
 * Read BYDAuto sensor/bodywork HAL (app-callable, like BYDAutoSpeedDevice) to corroborate/enrich Yandex:
 *  - rain sensor (getRainfall 0-14) -> overrides API weather with ground truth (HudWeatherApi.setCarRain)
 *  - turn signal / blinker -> lane-change intent (logged; can confirm Yandex lane guidance)
 *  - slope (getSlope ±60°) -> road grade (EV range / HUD), logged
 * All reflection; no-op off-car. Polls at 3 s.
 */
public final class HudSensors {

    private static volatile boolean sStarted;
    private static Context sCtx;
    private static Object sSensor, sBody;
    private static Method mRain, mSlope, mBlinkerL, mBlinkerR, mIndicator;
    private static Handler sBg;
    private static int sLog;

    public static void start(Context c) {
        if (sStarted || c == null) return;
        sStarted = true; sCtx = c.getApplicationContext();
        try {
            sSensor = hal("android.hardware.bydauto.sensor.BYDAutoSensorDevice");
            if (sSensor != null) {
                mRain  = m(sSensor, "getRainfall");
                mSlope = m(sSensor, "getSlope");
            }
            sBody = hal("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            if (sBody != null) {
                // blinker getter name varies by build — try the likely ones
                for (String n : new String[]{"getTurnLightStatus", "getTurnSignalStatus", "getIndicatorStatus", "getTurnLight"}) {
                    Method mm = m(sBody, n); if (mm != null) { mIndicator = mm; break; }
                }
            }
            HudLog.f("HudSensors sensor=" + (sSensor != null) + " rain=" + (mRain != null) + " body=" + (sBody != null) + " blinker=" + (mIndicator != null));
            HandlerThread t = new HandlerThread("hud-sensors"); t.start(); sBg = new Handler(t.getLooper());
            sBg.post(POLL);
        } catch (Throwable t) { HudLog.f("HudSensors start fail: " + t); }
    }

    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            try {
                if (mRain != null) {
                    Object r = mRain.invoke(sSensor);
                    if (r instanceof Number) { int lvl = ((Number) r).intValue();
                        HudWeatherApi.setCarRain(sCtx, lvl);
                        if (sLog < 4 && lvl != 0) { sLog++; HudLog.f("rainfall=" + lvl); } }
                }
                if (mIndicator != null) {
                    Object v = mIndicator.invoke(sBody);
                    if (sLog < 8) { sLog++; HudLog.f("blinker=" + v); }   // lane-change intent (calibrate)
                }
                if (mSlope != null && sLog < 6) {
                    Object s = mSlope.invoke(sSensor);
                    if (s instanceof Number && ((Number) s).intValue() != 0) { sLog++; HudLog.f("slope=" + s); }
                }
            } catch (Throwable t) {}
            if (sBg != null) sBg.postDelayed(this, 3000L);
        }
    };

    private static Object hal(String cls) {
        try { return Class.forName(cls).getMethod("getInstance", Context.class).invoke(null, sCtx); }
        catch (Throwable t) { return null; }
    }
    private static Method m(Object o, String name) {
        try { return o.getClass().getMethod(name); } catch (Throwable t) { return null; }
    }
}
