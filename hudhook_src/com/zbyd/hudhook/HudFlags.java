package com.zbyd.hudhook;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Runtime feature toggles (SharedPreferences "zbyd_hud"). Lets the user disable any auto-behavior
 * (automation, auto-start/preconditioning) without a rebuild — via voice ("включи/выключи автозапуск")
 * or an external `am`/settings write. Conservative defaults: safety-positive on, comfort/intrusive off.
 */
public final class HudFlags {

    public static final String MASTER     = "auto_master";     // global automation kill-switch
    public static final String AUTOSTART  = "autostart";       // preconditioning (climate on at route start)
    public static final String RAIN       = "auto_rain";       // rain -> close windows
    public static final String AMBIENT    = "auto_ambient";    // turn -> ambient pulse
    public static final String HEADLIGHT  = "auto_headlight";  // tunnel -> auto headlight (unvalidated)
    public static final String EV_WARN    = "auto_evwarn";     // route > range -> spoken warning
    public static final String TPMS       = "auto_tpms";       // low tire -> warning
    public static final String SEAT_HEAT  = "auto_seatheat";   // front seat heating at route start
    public static final String WHEEL_HEAT = "auto_wheelheat";  // steering-wheel heating at route start
    public static final String PANORAMA   = "auto_panorama";   // open panoramic roof at route start
    public static final String ADAS_ROUTE = "adas_route";      // EXPERIMENTAL: publish Yandex route to ADAS SOME/IP (L2 nav-assist)
    public static final String ADAS_NOA   = "adas_noa";        // EXPERIMENTAL: hand Yandex destination to Amap -> native NOA (legit HD)

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("zbyd_hud", Context.MODE_PRIVATE);
    }

    public static boolean def(String key) {
        // intrusive/comfort features off by default; safety-positive on
        return !(AUTOSTART.equals(key) || HEADLIGHT.equals(key) || SEAT_HEAT.equals(key)
                || WHEEL_HEAT.equals(key) || PANORAMA.equals(key) || ADAS_ROUTE.equals(key) || ADAS_NOA.equals(key));
    }

    public static boolean on(Context c, String key) {
        if (c == null) return def(key);
        try {
            if (!MASTER.equals(key) && !p(c).getBoolean(MASTER, true)) return false;   // master gate
            return p(c).getBoolean(key, def(key));
        } catch (Throwable t) { return def(key); }
    }

    public static void set(Context c, String key, boolean v) {
        if (c == null) return;
        try { p(c).edit().putBoolean(key, v).apply(); HudLog.f("FLAG " + key + "=" + v); } catch (Throwable t) {}
    }
}
