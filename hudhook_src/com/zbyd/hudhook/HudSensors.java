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
    private static Object sSensor, sBody, sAdas;
    private static Method mRain, mSlope, mBlinkerL, mBlinkerR, mIndicator, mLaneOffset, mSla;
    private static volatile int sLaneOffsetState;              // ADAS lane-departure state (0=none,L,R)
    private static volatile int sSlaState;                     // ADAS speed-limit-assist state
    public static int laneOffsetState() { return sLaneOffsetState; }
    public static int slaState() { return sSlaState; }
    private static Handler sBg;
    private static int sLog;
    private static volatile int sSlope;                        // road grade (deg, ±) — EV range / HUD
    public static int slope() { return sSlope; }

    public static void start(Context c) {
        if (sStarted || c == null) return;
        sStarted = true; sCtx = c.getApplicationContext();
        try {
            sSensor = hal("android.hardware.bydauto.sensor.BYDAutoSensorDevice");
            if (sSensor != null) {
                mRain  = m(sSensor, "getRainfall");
                mSlope = m(sSensor, "getSlope");
            }
            sAdas = hal("android.hardware.bydauto.adas.BYDAutoADASDevice");
            if (sAdas != null) {
                mLaneOffset = m(sAdas, "getLaneOffsetState");
                mSla = m(sAdas, "getSLAState");
            }
            sBody = hal("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            if (sBody != null) {
                // blinker getter name varies by build — try the likely ones
                for (String n : new String[]{"getTurnLightStatus", "getTurnSignalStatus", "getIndicatorStatus", "getTurnLight"}) {
                    Method mm = m(sBody, n); if (mm != null) { mIndicator = mm; break; }
                }
            }
            HudLog.f("HudSensors sensor=" + (sSensor != null) + " rain=" + (mRain != null) + " body=" + (sBody != null) + " blinker=" + (mIndicator != null) + " adas=" + (sAdas != null) + " laneOff=" + (mLaneOffset != null));
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
                        try { HudAutomation.onRain(lvl); } catch (Throwable t) {}   // auto-close windows in rain
                        if (sLog < 4 && lvl != 0) { sLog++; HudLog.f("rainfall=" + lvl); } }
                }
                if (mIndicator != null) {
                    Object v = mIndicator.invoke(sBody);
                    if (sLog < 8) { sLog++; HudLog.f("blinker=" + v); }   // lane-change intent (calibrate)
                }
                if (mSlope != null) {
                    Object s = mSlope.invoke(sSensor);
                    if (s instanceof Number) { sSlope = ((Number) s).intValue();
                        if (sLog < 6 && sSlope != 0) { sLog++; HudLog.f("slope=" + sSlope); } }
                }
                if (mLaneOffset != null) {
                    Object v = mLaneOffset.invoke(sAdas);
                    if (v instanceof Number) { int st = ((Number) v).intValue();
                        if (st != sLaneOffsetState && sLog < 10) { sLog++; HudLog.f("ADAS laneOffset=" + st); }
                        sLaneOffsetState = st; }
                }
                if (mSla != null) {
                    Object v = mSla.invoke(sAdas);
                    if (v instanceof Number) sSlaState = ((Number) v).intValue();
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
