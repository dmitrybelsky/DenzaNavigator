package com.zbyd.hudhook;

import android.content.Context;
import java.lang.reflect.Method;

/**
 * Yandex -> CLUSTER via the BYDAuto instrument HAL (android.hardware.bydauto.instrument.
 * BYDAutoInstrumentDevice). This is a TIER-1 channel that feeds the instrument cluster guidance
 * DIRECTLY, bypassing the vsomeip single-owner gate on launchermap's HUD topics — so Yandex turns show
 * on the cluster even while AMap/launchermap owns SVC_HUD_NAVI. App-callable like BYDAutoSpeedDevice.
 *
 * Methods (RE'd): sendAutoNaviStatus(int) [NAVI_INVALID=0/OPEN_NOT_SET_DEST=1/OPEN_SET_DEST=2/OPENING=3/
 * CLOSE=4], sendSimpleGuidanceInfo(int turnType, int distM), sendNextPathName(String),
 * sendCameraGuidanceInfo(int camType,int distM,int roadClass), sendDestinationSetStatus(int)
 * [NOT_SET=1/SET_DONE=2]. turnType enum is opaque (AMap icon set) — calibrate on car; start with our
 * launchermap bydIcon mapping which the cluster likely shares.
 */
public final class HudInstrumentHal {

    private static final int NAVI_OPEN_SET_DEST = 2;
    private static volatile Object sDev;
    private static volatile boolean sTried, sNaviSet;
    private static Method mSimple, mNext, mStatus, mCamera, mDest;
    private static int sLog;

    private static synchronized boolean resolve() {
        if (sDev != null) return true;
        if (sTried && sDev == null) return false;
        sTried = true;
        try {
            Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            Class<?> k = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            sDev = k.getMethod("getInstance", Context.class).invoke(null, c);
            mStatus = k.getMethod("sendAutoNaviStatus", int.class);
            mSimple = k.getMethod("sendSimpleGuidanceInfo", int.class, int.class);
            mNext   = k.getMethod("sendNextPathName", String.class);
            try { mCamera = k.getMethod("sendCameraGuidanceInfo", int.class, int.class, int.class); } catch (Throwable t) {}
            try { mDest = k.getMethod("sendDestinationSetStatus", int.class); } catch (Throwable t) {}
            HudLog.f("HudInstrumentHal resolved (" + (sDev != null) + ")");
            return sDev != null;
        } catch (Throwable t) { HudLog.f("HudInstrumentHal resolve fail: " + t); return false; }
    }

    private static volatile boolean sDirectDead;

    /** Push one Yandex maneuver to the cluster: turnType (bydIcon), road name, distance (m).
     *  The instrument HAL is signature-gated (BYDAUTO_INSTRUMENT_SET) so a re-signed Yandex can't
     *  write it directly — route through the privileged agent (HudPrivClient -> HudPrivAgent injected
     *  via JDWP into a perm-holding debuggable BYD app). A direct attempt stays as a phone fallback. */
    public static void pushManeuver(int turnType, String road, int distM) {
        HudPrivClient.cluster(turnType, distM, road);           // privileged channel (primary)
        if (sDirectDead) return;
        if (!resolve()) { sDirectDead = true; return; }
        try {
            if (!sNaviSet) { sNaviSet = true; try { mStatus.invoke(sDev, NAVI_OPEN_SET_DEST); } catch (Throwable t) {} }
            int r1 = (Integer) mSimple.invoke(sDev, turnType, distM);
            if (road != null && road.length() > 0) try { mNext.invoke(sDev, road); } catch (Throwable t) {}
            if (sLog++ < 3) HudLog.f("HAL direct maneuver type=" + turnType + " dist=" + distM + " ret=" + r1);
        } catch (Throwable t) { sDirectDead = true; }           // perm deny -> stop direct, agent carries it
    }

    /** Speed-camera/safety advisory via the privileged agent (camType opaque — calibrate on car). */
    public static void pushCamera(int camType, int distM, int roadClass) {
        HudPrivClient.camera(camType, distM, roadClass);
    }
}
