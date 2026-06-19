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
    private static final long TOPIC_HUD_ROAD_INFO = 1127042368241665L;  // 0x4010a00018001 — windshield HUD text+AR
    private static final long TOPIC_HUD_MAP = 1127042368241667L;        // 0x4010a00018003 — HUD_NAVIGATIONMAP raster
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

    private static final java.util.HashSet<Long> sStartedSvcs = new java.util.HashSet<Long>();
    /** Offer an arbitrary SOME/IP service (generic startSomeIpService). Idempotent per serviceId. */
    private static synchronized void startSvc(long sid) {
        IBinder b = sBinder; if (b == null || sStartedSvcs.contains(sid)) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR); d.writeLong(sid);
            b.transact(TX_START, d, r, 0); r.readException();
            int rc = r.readInt(); if (rc == 0 || rc == 13) sStartedSvcs.add(sid);
        } catch (Throwable t) {} finally { r.recycle(); d.recycle(); }
    }

    /** EXPERIMENTAL: offer <sid> + fireEvent(<topic>, payload). Used by HudAdasRoute (route injection). */
    public static int pushEvent(long sid, long topic, byte[] payload) { startSvc(sid); return fire(topic, payload); }

    /** Publish one windshield HUD_ROAD_INFO frame (665): real Yandex position + route guideLine
     *  (AR arrows follow the actual route ahead) + road/dist/eta/maneuver. lat/lon are the live
     *  vehicle position; guideLine is the forward route polyline "[[lon,lat,0],...]" (null = synthetic). */
    private static int sHudLog;
    public static void pushHud(Context c, String road, int distM, String eta, int bydIcon,
                               double lat, double lon, String guideLine,
                               int etaRemainSec, String lanes, int numLanes, int speedLimit) {
        ensure(c); start();
        if (sBinder == null) { if (sHudLog++ < 3) HudLog.f("pushHud: no SOME/IP service (phone) road=" + road + " dist=" + distM + " icon=" + bydIcon); return; }
        if (lat == 0 && lon == 0) { lat = HUD_LAT; lon = HUD_LON; }   // pre-fix fallback
        String gl = (guideLine == null || guideLine.length() < 4) ? guideLine(bydIcon, lat, lon) : guideLine;
        int r = fire(TOPIC_HUD_ROAD_INFO, hudRoadInfoMsg(road, distM, eta, bydIcon, sCounter++ & 0xff, lat, lon, gl, etaRemainSec, lanes, numLanes, speedLimit));
        if (sHudLog++ < 8) HudLog.f("pushHud fire ret=" + r + " road=" + road + " dist=" + distM + " icon=" + bydIcon + " eta=" + eta + " remain=" + etaRemainSec + " lanes=" + numLanes + " spdLim=" + speedLimit + " pos=" + lat + "," + lon + " gl=" + (gl == null ? 0 : gl.length()));
    }

    /** Publish the live Yandex map raster to the HUD map panel (event 0x8003 HudNavigationmap{f1=Base64 PNG}). */
    private static int sMapLog;
    public static void pushMap(Context c, byte[] pngBytes) {
        ensure(c); start();
        if (sBinder == null || pngBytes == null || pngBytes.length == 0) return;
        String b64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP);
        ByteArrayOutputStream s = new ByteArrayOutputStream(b64.length() + 8);
        sfield(s, 1, b64);                                   // HudNavigationmap.navigationMap_ = f1
        int r = fire(TOPIC_HUD_MAP, embed(1, s.toByteArray()));
        if (sMapLog++ < 6) HudLog.f("pushMap fire ret=" + r + " png=" + pngBytes.length + " b64=" + b64.length());
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

    // --- protobuf HudRoadInfoNotifyStruct (EG-wrapped) — matches the proven launchermap reference
    //     (HudNaviController.buildRoadInfo): f2 counter, f9 dist, f10 road, f16=2 navigatingStatus,
    //     f19 lon, f20 lat, f26 eta, f28 maneuver/turn-id, f30 guideLine (AR arrows), f31 "lon,lat,0".
    private static byte[] hudRoadInfoMsg(String road, int distM, String eta, int maneuver, int counter,
                                         double lat, double lon, String guideLine,
                                         int etaRemainSec, String lanes, int numLanes, int speedLimit) {
        ByteArrayOutputStream s = new ByteArrayOutputStream(96);
        vfield(s, 2, counter);
        if (numLanes > 0) vfield(s, 5, numLanes);          // f5 NUM_OF_LANES
        byte[] icon = null;
        try { icon = HudManeuverIcon.png(maneuver); } catch (Throwable t) {}   // f8 maneuver-arrow PNG
        if (icon != null && icon.length > 0) bfield(s, 8, icon);
        vfield(s, 9, distM);                                // f9 DISTANCE_2_INTERSECTION
        sfield(s, 10, road == null ? "" : road);           // f10 NEXT_ROAD_NAME
        if (speedLimit > 0) { vfield(s, 11, speedLimit); vfield(s, 15, speedLimit); } // f11/f15 speed limit
        vfield(s, 16, 2);                                    // f16 NAVIGATING_STATUS = navigating
        dfield(s, 19, lon); dfield(s, 20, lat);             // f19/f20 live vehicle position
        sfield(s, 26, eta == null ? "" : eta);             // f26 ETA_INFO_TIME (arrival clock)
        if (etaRemainSec > 0) vfield(s, 27, etaRemainSec); // f27 ETA_INFO_REMAIN_TIME (sec)
        vfield(s, 28, maneuver);                            // f28 maneuver/turn-id
        if (lanes != null && lanes.length() > 0) sfield(s, 29, lanes); // f29 LANESPERMISSIBLEDIRECTIONID
        if (guideLine != null && guideLine.length() >= 4) sfield(s, 30, guideLine); // f30 GUIDELINE AR-arrows
        sfield(s, 31, lon + "," + lat + ",0");             // f31 GUIDEPOINT
        return embed(1, s.toByteArray());
    }

    /** Synthetic fallback guideLine when real route geometry is unavailable: short forward stub from
     *  the live position, bent by maneuver direction. Real AR uses the Yandex route polyline (HudEvents). */
    private static String guideLine(int m, double lat0, double lon0) {
        StringBuilder b = new StringBuilder("[");
        for (int k = 0; k <= 9; k++) {
            double lat = lat0 + k * 0.0002;
            double turn = k > 5 ? (k - 5) * 0.0002 : 0.0;
            double lon = (m == 2 || m == 4 || m == 6 || m == 8 || m == 17) ? lon0 - turn
                       : (m == 3 || m == 5 || m == 7 || m == 18) ? lon0 + turn : lon0;
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
    private static void bfield(ByteArrayOutputStream o, int f, byte[] b) {
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
