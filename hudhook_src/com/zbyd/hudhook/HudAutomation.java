package com.zbyd.hudhook;

import android.content.Context;

/**
 * Event-driven car automation: reacts to Yandex nav + BYD sensors and writes the car via the
 * shell-uid autoservice helper (HudCarClient). All rules gated by conservative flags. dev/fid are
 * BYDMate live-validated (Leopard3) — verify on N9; unvalidated ones (headlight) default OFF.
 *
 * Rules: rain → close windows · route-start → AC (opt-in) · turn → ambient pulse · tunnel → auto-
 * headlight (opt-in/unvalidated) · route too long vs EV range → spoken warning · low TPMS → warning.
 */
public final class HudAutomation {

    // toggles are runtime (HudFlags) so any auto-behavior is disableable (voice / settings)

    // autoservice dev/fid (BYDMate-validated unless noted)
    private static final int DEV_BODY = 1001, DEV_CLIMATE = 1000, DEV_LIGHT = 1023;
    private static final int[] WINDOW_POS = {1276219408, 1276219416, 1276219424, 1276219432}; // LF,LR,RF,RR
    private static final int[] TPMS_FID = {947912728, 947912736, 947912744, 947912752};        // 4th guessed (+8)
    private static final int TPMS_MIN_KPA = 180;
    private static final int FID_AUTO_LIGHT = 0x32B0D010;   // LIGHT_AUTOMATIC_LIGHT_MODE_SET (unvalidated dev)

    private static Context ctx;
    private static boolean acDone, evWarnDone, ambientOn, tpmsWarned;
    private static long lastRainClose, lastTpms;

    public static void init(Context c) { ctx = c; }

    /** Called on each route (re)start — reset per-route latches. */
    public static void onRouteStart() {
        acDone = false; evWarnDone = false; tpmsWarned = false;
        if (HudFlags.on(ctx, HudFlags.AUTOSTART)) { try { HudCarClient.ac(true); HudLog.f("AUTO route-start -> AC on"); } catch (Throwable t) {} }
    }

    /** Rain level 0-14 (BYDAutoSensorDevice.getRainfall). Close windows when it actually rains. */
    public static void onRain(int level) {
        if (!HudFlags.on(ctx, HudFlags.RAIN) || level < 4) return;
        long now = now(); if (now - lastRainClose < 60000) return; lastRainClose = now;
        try { for (int fid : WINDOW_POS) HudCarClient.write(DEV_BODY, fid, 0); }   // 0 = fully closed
        catch (Throwable t) {}
        HudLog.f("AUTO rain=" + level + " -> windows closed");
    }

    /** Nearest maneuver (bydIcon + distance m). Pulse ambient near a turn; auto-AC once if enabled. */
    public static void onManeuver(int bydIcon, int distM) {
        if (HudFlags.on(ctx, HudFlags.AMBIENT)) {
            boolean turn = (bydIcon == 2 || bydIcon == 3 || bydIcon == 4 || bydIcon == 5
                    || bydIcon == 6 || bydIcon == 7 || bydIcon == 17 || bydIcon == 18);
            if (turn && distM > 0 && distM < 150 && !ambientOn) {
                ambientOn = true; try { HudCarClient.ambientLight(true); } catch (Throwable t) {}
            } else if (ambientOn && distM > 220) {
                ambientOn = false; try { HudCarClient.ambientLight(false); } catch (Throwable t) {}
            }
        }
        if (HudFlags.on(ctx, HudFlags.AUTOSTART) && !acDone) { acDone = true; try { HudCarClient.ac(true); } catch (Throwable t) {} }
    }

    /** Tunnel ahead/inside (Yandex). Optionally enable auto-headlight mode (unvalidated fid). */
    public static void onTunnel(int distAheadM, boolean inside) {
        if (HudFlags.on(ctx, HudFlags.HEADLIGHT) && inside) {
            try { HudCarClient.write(DEV_LIGHT, FID_AUTO_LIGHT, 1); HudLog.f("AUTO tunnel -> auto-light"); }
            catch (Throwable t) {}
        }
    }

    /** Remaining route distance (m) vs EV range — warn once if it may not reach. */
    public static void onRouteProgress(int remainingM) {
        if (!HudFlags.on(ctx, HudFlags.EV_WARN) || evWarnDone) return;
        double range = HudCarClient.rangeKm();
        if (range > 10 && remainingM / 1000.0 > range * 0.95) {
            evWarnDone = true;
            try { HudTts.say(ctx, "Внимание, запаса хода может не хватить до конца маршрута"); } catch (Throwable t) {}
            HudLog.f("AUTO EV-warn route=" + (remainingM / 1000) + "km range=" + (int) range + "km");
        }
        checkTpms();
    }

    private static void checkTpms() {
        if (!HudFlags.on(ctx, HudFlags.TPMS) || tpmsWarned) return;
        long now = now(); if (now - lastTpms < 30000) return; lastTpms = now;
        try {
            int low = -1, lowVal = 0;
            for (int i = 0; i < TPMS_FID.length; i++) {
                int p = HudCarClient.read(DEV_BODY, TPMS_FID[i]);          // kPa (validate scale on N9)
                if (p > 50 && p < TPMS_MIN_KPA && (low < 0 || p < lowVal)) { low = i; lowVal = p; }
            }
            if (low >= 0) {
                tpmsWarned = true;
                String[] w = {"переднем левом", "заднем левом", "переднем правом", "заднем правом"};
                try { HudTts.say(ctx, "Низкое давление в " + w[low] + " колесе"); } catch (Throwable t) {}
                HudLog.f("AUTO TPMS low tire=" + low + " kPa=" + lowVal);
            }
        } catch (Throwable t) {}
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }
}
