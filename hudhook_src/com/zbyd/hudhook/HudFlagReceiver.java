package com.zbyd.hudhook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * External flag control / ADAS kill-switch. Lets a phone or laptop over adb flip any HudFlags key at
 * runtime WITHOUT voice and without a rebuild — critical for the closed-road NOA test (instant abort
 * from the passenger seat). Dynamically registered (no host-APK manifest edit) and EXPORTED so the
 * shell `am` can reach it.
 *
 *   adb shell am broadcast -a com.zbyd.hud.FLAG --es key adas_noa  --ez val false   # stop NOA
 *   adb shell am broadcast -a com.zbyd.hud.FLAG --es key auto_master --ez val false  # stop ALL automation
 *   adb shell am broadcast -a com.zbyd.hud.FLAG --es key adas_noa  --ez val true    # re-arm NOA
 */
public final class HudFlagReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.zbyd.hud.FLAG";
    private static boolean sReg;

    /** Idempotent — safe to call from the poll loop each tick. */
    public static void register(Context ctx) {
        if (sReg || ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            IntentFilter f = new IntentFilter(ACTION);
            if (android.os.Build.VERSION.SDK_INT >= 33)
                app.registerReceiver(new HudFlagReceiver(), f, Context.RECEIVER_EXPORTED);   // A13+ needs explicit export
            else
                app.registerReceiver(new HudFlagReceiver(), f);
            sReg = true;
            HudLog.f("FlagReceiver registered (" + ACTION + ")");
        } catch (Throwable t) { HudLog.f("FlagReceiver reg: " + t); }
    }

    @Override
    public void onReceive(Context ctx, Intent it) {
        if (it == null || !ACTION.equals(it.getAction())) return;
        try {
            String key = it.getStringExtra("key");
            if (key == null || key.isEmpty()) { HudLog.f("FLAG broadcast: no key"); return; }
            boolean val = parseVal(it);
            HudFlags.set(ctx, key, val);                 // HudFlags.set persists to SharedPreferences
            HudLog.f("FLAG via broadcast " + key + "=" + val);
        } catch (Throwable t) { HudLog.f("FlagReceiver recv: " + t); }
    }

    /** Accept --ez val true/false OR --es val on/off/1/0/true/false. Missing -> false (fail-safe to OFF). */
    private static boolean parseVal(Intent it) {
        if (!it.hasExtra("val")) return false;
        Object o = it.getExtras().get("val");
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o).trim();
        return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on");
    }
}
