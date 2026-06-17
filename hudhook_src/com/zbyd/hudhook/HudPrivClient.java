package com.zbyd.hudhook;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.OutputStream;

/**
 * Client to the privileged HUD agent (HudPrivAgent), injected via JDWP into a debuggable BYD app that
 * holds BYDAUTO_INSTRUMENT_SET / BYDAUTO_SETTING_SET. The re-signed Yandex can't write the cluster
 * itself (signature perm), so it streams nav commands over the abstract LocalSocket "zbyd_hud_priv";
 * the agent replays them with the privileged UID. No-op (silent) when the agent isn't present
 * (e.g. phone, or before injection) — connect() back-offs to avoid spam.
 */
public final class HudPrivClient {

    private static final String SOCK = "zbyd_hud_priv";
    private static volatile LocalSocket sSock;
    private static volatile OutputStream sOut;
    private static long sLastTry;
    private static int sLog;

    private static synchronized boolean connect() {
        if (sOut != null) return true;
        long now = System.currentTimeMillis();
        if (now - sLastTry < 3000) return false;                 // back-off when agent absent
        sLastTry = now;
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
            sSock = s; sOut = s.getOutputStream();
            HudLog.f("HudPrivClient connected @" + SOCK);
            return true;
        } catch (Throwable t) {
            if (sLog++ < 3) HudLog.f("HudPrivClient no agent (@" + SOCK + "): " + t);
            return false;
        }
    }

    private static void send(String line) {
        if (!connect()) return;
        try {
            sOut.write((line + "\n").getBytes("UTF-8"));
            sOut.flush();
        } catch (Throwable t) {
            sOut = null;
            try { sSock.close(); } catch (Throwable e) {}
            sSock = null;
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

    // Seat comfort (BYDAutoSettingDevice.setSeat{Heating,Ventilating}State(seat, level); seat 1..n, level 0-3).
    public static void seatHeat(int seat, int level) { setting("setSeatHeatingState", seat, level); }
    public static void seatVent(int seat, int level) { setting("setSeatVentilatingState", seat, level); }

    /** Write a raw BYD instrument feature-id (hex "0x..") — cluster nav fields (ETA/mileage/trip/safety). */
    public static void fidSet(String hexFid, int val) { send("FIDSET " + hexFid + " " + val); }
}
