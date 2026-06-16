package com.zbyd.hudhook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;

/**
 * In-process SOME/IP publisher (ported from hud-bench SomeIpMatrix/SomeIpHudClient) so the modded
 * Yandex pushes the BYD windshield-HUD / cluster events DIRECTLY — no separate app, no bilithings
 * broadcast. Binds the system TS SOME/IP service (exported, no permission) and fireEvent()s the
 * HudRoadInfo / SD-map topics the BYD HUD-ECU + cluster consume.
 */
public final class HudSomeIp {

    private static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    private static final String ACTION = "com.ts.car.someip.SomeIpServerService";
    private static final String PKG = "com.ts.car.someip.service";
    private static final int TX_START = 4, TX_FIRE = 6;

    private static final long SVC_HUD_NAVI = 3097367205183488L;
    private static final long SVC_SD_MAP = 3239172026531840L;
    private static final long TOPIC_HUD_ROAD_INFO = 1127042368241665L;  // 665 — windshield HUD text+AR
    private static final long TOPIC_WEATHER = 1268847189590025L;        // regionalAndWeather
    private static final long TOPIC_FACILITY = 1268847189590021L;       // roadFacilities (parking)
    private static final long TOPIC_TUNNEL = 1268847189590020L;         // tunnel
    private static final long TOPIC_LIGHT = 1268847189590017L;          // sdTrafficLight
    private static final double HUD_LON = 37.5959188888889, HUD_LAT = 55.9571725;
    private static volatile boolean sSdStarted;

    private static volatile IBinder sBinder;
    private static volatile boolean sStarted;
    private static int sCounter;

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { sBinder = b; }
        @Override public void onServiceDisconnected(ComponentName n) { sBinder = null; sStarted = false; }
    };

    static void ensure(Context c) {
        if (sBinder != null) return;
        try {
            c.getApplicationContext().bindService(
                new Intent(ACTION).setPackage(PKG), CONN, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {}
    }

    private static void start() {
        IBinder b = sBinder; if (b == null || sStarted) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR); d.writeLong(SVC_HUD_NAVI);
            b.transact(TX_START, d, r, 0); r.readException();
            int rc = r.readInt(); sStarted = (rc == 0 || rc == 13);
        } catch (Throwable t) {} finally { r.recycle(); d.recycle(); }
    }

    private static int fire(long topic, byte[] payload) {
        IBinder b = sBinder; if (b == null) return -100;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(1); d.writeLong(topic); d.writeLong(0L);
            d.writeInt(payload.length); d.writeByteArray(payload);
            b.transact(TX_FIRE, d, r, 0); r.readException(); return r.readInt();
        } catch (Throwable t) { return -200; } finally { r.recycle(); d.recycle(); }
    }

    /** Publish one windshield HUD_ROAD_INFO frame (665): road + dist + eta + maneuver + AR guideLine. */
    private static int sHudLog;
    public static void pushHud(Context c, String road, int distM, String eta, int bydIcon) {
        ensure(c); start();
        if (sBinder == null) { if (sHudLog++ < 3) HudLog.f("pushHud: no SOME/IP service (phone) road=" + road + " dist=" + distM + " icon=" + bydIcon); return; }
        int r = fire(TOPIC_HUD_ROAD_INFO, hudRoadInfoMsg(road, distM, eta, bydIcon, sCounter++ & 0xff));
        if (sHudLog++ < 5) HudLog.f("pushHud fire ret=" + r + " road=" + road + " dist=" + distM + " icon=" + bydIcon);
    }

    private static void startSd() {
        IBinder b = sBinder; if (b == null || sSdStarted) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR); d.writeLong(SVC_SD_MAP);
            b.transact(TX_START, d, r, 0); r.readException();
            int rc = r.readInt(); sSdStarted = (rc == 0 || rc == 13);
        } catch (Throwable t) {} finally { r.recycle(); d.recycle(); }
    }

    private static void fireSd(Context c, long topic, byte[] inner) {
        ensure(c); startSd();
        if (sBinder == null) return;
        fire(topic, embed(1, inner));   // every *Notify wraps the struct at field 1
    }

    /** regionalAndWeather{weatherType:5} — Yandex condition → BYD weather code. */
    public static void pushWeather(Context c, int weatherType) {
        ByteArrayOutputStream s = new ByteArrayOutputStream(8);
        vfield(s, 5, weatherType);
        fireSd(c, TOPIC_WEATHER, s.toByteArray());
    }

    /** roadFacilities{naviType:1, dist:3, lon:4, lat:5} — parking POI. */
    public static void pushFacility(Context c, int naviType, int distM, double lon, double lat) {
        ByteArrayOutputStream s = new ByteArrayOutputStream(32);
        vfield(s, 1, naviType); vfield(s, 3, distM); dfield(s, 4, lon); dfield(s, 5, lat);
        fireSd(c, TOPIC_FACILITY, s.toByteArray());
    }

    /** tunnel{states:1, sLat:2, sLon:3, eLat:4, eLon:5, toDist:6}. */
    public static void pushTunnel(Context c, int distM, double sLat, double sLon, double eLat, double eLon) {
        ByteArrayOutputStream s = new ByteArrayOutputStream(48);
        vfield(s, 1, 1); dfield(s, 2, sLat); dfield(s, 3, sLon);
        dfield(s, 4, eLat); dfield(s, 5, eLon); vfield(s, 6, distM);
        fireSd(c, TOPIC_TUNNEL, s.toByteArray());
    }

    /** sdTrafficLight{exist:1, lat:2, dist:10}. */
    public static void pushLight(Context c, double lat, double lon, int distM) {
        ByteArrayOutputStream s = new ByteArrayOutputStream(24);
        vfield(s, 1, 1); dfield(s, 2, lat); vfield(s, 10, distM);
        fireSd(c, TOPIC_LIGHT, s.toByteArray());
    }

    /** sdTrafficLight{exist:1, lightWaitNum:8 = countdown seconds (OCR'd)}. */
    public static void pushLightCountdown(Context c, int seconds) {
        ByteArrayOutputStream s = new ByteArrayOutputStream(12);
        vfield(s, 1, 1); vfield(s, 8, seconds);
        fireSd(c, TOPIC_LIGHT, s.toByteArray());
    }

    // --- protobuf HudRoadInfoNotifyStruct (EG-wrapped), ported from hud-bench ----------------
    private static byte[] hudRoadInfoMsg(String road, int distM, String eta, int maneuver, int counter) {
        ByteArrayOutputStream s = new ByteArrayOutputStream(96);
        vfield(s, 1, 0); vfield(s, 2, counter);
        vfield(s, 3, distM); vfield(s, 4, distM / 12);
        vfield(s, 6, 8); vfield(s, 9, distM);
        sfield(s, 10, road == null ? "" : road);
        vfield(s, 12, 60); vfield(s, 16, 1);
        dfield(s, 19, HUD_LON); dfield(s, 20, HUD_LAT);
        sfield(s, 26, eta == null ? "" : eta); vfield(s, 27, distM / 12);
        vfield(s, 28, maneuver);                              // f28 maneuver/turn-id
        sfield(s, 30, guideLine(maneuver));                  // f30 AR road-arrow polyline
        sfield(s, 31, HUD_LON + "," + HUD_LAT + ",0");
        return embed(1, s.toByteArray());
    }

    private static String guideLine(int m) {
        StringBuilder b = new StringBuilder("[");
        for (int k = 0; k <= 9; k++) {
            double lat = HUD_LAT + k * 0.0002;
            double turn = k > 5 ? (k - 5) * 0.0002 : 0.0;
            double lon = (m == 2 || m == 4 || m == 6 || m == 8 || m == 17) ? HUD_LON - turn
                       : (m == 3 || m == 5 || m == 7 || m == 18) ? HUD_LON + turn : HUD_LON;
            b.append("[").append(lon).append(",").append(lat).append(",0]");
            if (k < 9) b.append(",");
        }
        return b.append("]").toString();
    }

    private static byte[] embed(int field, byte[] msg) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(msg.length + 4);
        varint(o, (field << 3) | 2); varint(o, msg.length); o.write(msg, 0, msg.length);
        return o.toByteArray();
    }
    private static void vfield(ByteArrayOutputStream o, int f, long v) { varint(o, f << 3); varint(o, v); }
    private static void sfield(ByteArrayOutputStream o, int f, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        varint(o, (f << 3) | 2); varint(o, b.length); o.write(b, 0, b.length);
    }
    private static void dfield(ByteArrayOutputStream o, int f, double v) {
        varint(o, (f << 3) | 1); long bits = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) o.write((int) ((bits >>> (8 * i)) & 0xff));
    }
    private static void varint(ByteArrayOutputStream o, long v) {
        while (true) { if ((v & ~0x7FL) == 0) { o.write((int) v); return; } o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
    }
}
