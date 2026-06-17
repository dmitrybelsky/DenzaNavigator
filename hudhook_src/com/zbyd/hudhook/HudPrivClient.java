package com.zbyd.hudhook;

import java.io.File;
import java.io.FileWriter;

/**
 * Client to the privileged HUD agent (HudPrivAgent), injected via JDWP into com.byd.cameraautostudy
 * (platform_app; holds BYDAUTO_INSTRUMENT_SET / BODYWORK_SET / SETTING_SET). The re-signed Yandex
 * (untrusted_app) can't write the cluster/body itself (signature perm) AND can't reach the agent's
 * abstract socket (SELinux denies untrusted_app -> platform_app connectto). So commands cross the
 * domain barrier via a /sdcard FILE BRIDGE: /sdcard is mlstrustedobject (MLS-exempt) and both sides
 * hold MANAGE_EXTERNAL_STORAGE (granted once via `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`).
 * We atomically publish each command (tmp -> rename); the agent polls, executes with its UID, replies.
 * No-op when the bridge dir isn't writable (phone, or before grant).
 */
public final class HudPrivClient {

    private static final File DIR = new File("/sdcard/zbyd");
    private static final File CMD = new File(DIR, "cmd");
    private static final File CMD_TMP = new File(DIR, "cmd.tmp");
    private static volatile boolean sReady, sChecked;
    private static int sLog;

    private static synchronized boolean ready() {
        if (sChecked) return sReady;
        sChecked = true;
        try { DIR.mkdirs(); sReady = DIR.canWrite() || DIR.exists(); }
        catch (Throwable t) { sReady = false; }
        HudLog.f("HudPrivClient bridge " + DIR + " ready=" + sReady);
        return sReady;
    }

    private static synchronized void send(String line) {
        if (!ready()) return;
        try {
            FileWriter w = new FileWriter(CMD_TMP); w.write(line + "\n"); w.close();
            CMD_TMP.renameTo(CMD);                                // atomic publish; agent polls + executes
        } catch (Throwable t) {
            if (sLog++ < 3) HudLog.f("HudPrivClient bridge write fail: " + t);
        }
    }

    public static void cluster(int turnType, int distM, String road) {
        String r = (road == null || road.isEmpty()) ? "" : " " + road.replace('\n', ' ').replace('\r', ' ');
        send("CLUSTER " + turnType + " " + distM + r);
    }

    public static void camera(int camType, int distM, int roadClass) {
        send("CAMERA " + camType + " " + distM + " " + roadClass);
    }

    public static void status(int n) { send("STATUS " + n); }

    /** Invoke a BYDAutoSettingDevice setter (HUD layout / nav-map / seat heat-vent) by method name + int args. */
    public static void setting(String method, int... vals) {
        StringBuilder sb = new StringBuilder("SETTING ").append(method);
        for (int v : vals) sb.append(' ').append(v);
        send(sb.toString());
    }

    /** Invoke a BYDAutoBodyworkDevice setter (windows/doors/moonroof/trunk) by method name + int args. */
    public static void body(String method, int... vals) {
        StringBuilder sb = new StringBuilder("BODY ").append(method);
        for (int v : vals) sb.append(' ').append(v);
        send(sb.toString());
    }

    // Seat comfort (BYDAutoSettingDevice.setSeat{Heating,Ventilating}State(seat, level); seat 1..n, level OFF=1/LOW=2/HIGH=3).
    public static void seatHeat(int seat, int level) { setting("setSeatHeatingState", seat, level); }
    public static void seatVent(int seat, int level) { setting("setSeatVentilatingState", seat, level); }
    // Steering-wheel heat (setSteeringWheelHeatingState: OFF=1, ON=2).
    public static void wheelHeat(boolean on) { setting("setSteeringWheelHeatingState", on ? 2 : 1); }

    /** Write a raw BYD instrument feature-id (hex "0x..") — cluster nav fields (ETA/mileage/trip/safety). */
    public static void fidSet(String hexFid, int val) { send("FIDSET " + hexFid + " " + val); }

    /** Write a raw BYD bodywork feature-id (dev 1001) — e.g. rear sunshade (no high-level wrapper exists). */
    public static void bodyFid(int fid, int val) { send("BODYFID " + fid + " " + val); }

    // --- Panorama sunshades (N9 roof = fixed glass + electric shades; config 8 = front+rear sunshade) ---
    // Front shade: setSunshadeState(pct) 0=closed/100=open (validated live on N9). Rear: raw fid (no method).
    private static final int REAR_SUNSHADE_PERCENT_FID = 1276178472;   // BODYWORK_REAR_SUNSHADE_PANEL_PERCENT_SET (CanFD)
    public static void sunshade(int pct)     { body("setSunshadeState", pct); }
    public static void rearSunshade(int pct) { bodyFid(REAR_SUNSHADE_PERCENT_FID, pct); }
}
