package com.zbyd.hudhook;

import android.content.Context;

import java.io.ByteArrayOutputStream;

/**
 * EXPERIMENTAL — OFF BY DEFAULT. Publishes a Yandex-derived route onto the BYD SOME/IP topic that the
 * native ADAS consumes (NAVI_SD_ROUTE_NOTIFY, service NAVIGATION_SD_LINK2_SERVICE), via the same no-perm
 * ISomeIpServerInterface fireEvent path we use for the HUD. The intent is L2 nav-assist (lane-change /
 * exit hints follow our route); the actual lateral/longitudinal CONTROL stays in the camera-vetoed native
 * ADAS — this only supplies the navigation route, it does NOT command steering.
 *
 * ⚠️ SAFETY / HONESTY:
 *  - L2 only. L3/NOA additionally needs an HD map (HD link/lane IDs validated against an on-device HD-map
 *    DB in native ADAS) — synthetic/Yandex routes fail that native check. This class does NOT enable L3.
 *  - The native ADAS may validate the producer (uid/package) or the route (CRC/link semantics) below the
 *    Java layer; whether it accepts our publish is UNVERIFIED and only observable on a real vehicle.
 *  - Co-publishing conflicts with the stock map (Amap/launchermap). Disable it first to be sole producer:
 *      adb shell pm disable-user com.byd.launchermap   (re-enable: pm enable com.byd.launchermap)
 *  - LIVE TEST ONLY on a closed/empty road, driver fully supervising, hands on wheel. Gated behind
 *    HudFlags.ADAS_ROUTE (default false); never auto-enabled.
 *
 * Wire format (protobuf, reversed from SomeipNavigationSdLink2Service):
 *   naviSDRouteNotify{1: naviSDRouteStruct}
 *   naviSDRouteStruct{1 crc:int, 2 counter:int, 3 num:int, 4 navigationSDLink2:repeated HDLink2InfoStruct}
 *   HDLink2InfoStruct{1 checksum, 2 counter, 3 pathValid1, 4 pntCnt1, 5 linkCnt1, 6 pathId1,
 *                     7 linkItemArray1:repeated LinkItem, 8 pointItemArray1:repeated PntItem}
 *   LinkItem{1 formway:int, 2 linktype:int, 3 roadclass:int, 4 begIdx:int, 5 pntCnt:int, 6 roadname:str, 7 len:float}
 *   PntItem{1 x:double(lat), 2 y:double(lon)}
 */
public final class HudAdasRoute {

    private static final long SVC_SD_LINK2 = 3096409430228992L;
    private static final long TOPIC_SD_ROUTE = 1126084593287170L;   // NAVI_SD_ROUTE_NOTIFY
    private static int sCounter;
    private static long sLastPub;

    /** Build + publish the SD route from a Yandex polyline. No-op unless HudFlags.ADAS_ROUTE is on. */
    public static void publish(Context ctx, double[] lat, double[] lon, float totalMeters, int roadClass) {
        if (ctx == null || !HudFlags.on(ctx, HudFlags.ADAS_ROUTE)) return;
        if (lat == null || lon == null || lat.length < 2 || lat.length != lon.length) return;
        long now = System.currentTimeMillis();
        if (now - sLastPub < 500) return; sLastPub = now;            // throttle ~2 Hz
        try {
            int n = lat.length, ctr = ++sCounter;
            ByteArrayOutputStream pts = new ByteArrayOutputStream();
            for (int i = 0; i < n; i++) {                            // pointItemArray (field 8 of struct)
                byte[] p = cat(f64(1, lat[i]), f64(2, lon[i]));
                lenField(pts, 8, p);
            }
            byte[] link = cat(varint(1, 1), varint(2, 1), varint(3, roadClass), varint(4, 0),
                              varint(5, n), strf(6, ""), f32(7, totalMeters));   // one LinkItem covering route
            ByteArrayOutputStream hd = new ByteArrayOutputStream();
            hd.write(varint(1, 0)); hd.write(varint(2, ctr)); hd.write(varint(3, 1));   // checksum, counter, pathValid1
            hd.write(varint(4, n)); hd.write(varint(5, 1)); hd.write(varint(6, 1));     // pntCnt1, linkCnt1, pathId1
            lenField(hd, 7, link); hd.write(pts.toByteArray());                          // linkItemArray1 + pointItemArray1
            ByteArrayOutputStream st = new ByteArrayOutputStream();
            st.write(varint(1, 0)); st.write(varint(2, ctr)); st.write(varint(3, 1));   // crc, counter, num
            lenField(st, 4, hd.toByteArray());                                          // navigationSDLink2[0]
            ByteArrayOutputStream notify = new ByteArrayOutputStream();
            lenField(notify, 1, st.toByteArray());                                      // naviSDRouteStruct
            int rc = HudSomeIp.pushEvent(SVC_SD_LINK2, TOPIC_SD_ROUTE, notify.toByteArray());
            HudLog.f("ADAS route pub n=" + n + " len=" + (int) totalMeters + " rc=" + rc);
        } catch (Throwable t) { HudLog.f("ADAS route fail: " + t); }
    }

    /** Convenience: parse the AR guideLine polyline "[[lon,lat,0],...]" and publish it. No-op when gated off. */
    public static void publishGl(Context ctx, String gl) {
        if (ctx == null || gl == null || gl.length() < 8 || !HudFlags.on(ctx, HudFlags.ADAS_ROUTE)) return;
        try {
            String s = gl.trim();
            if (s.startsWith("[")) s = s.substring(1);
            if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
            String[] tk = s.split("\\],\\[");
            int n = tk.length; if (n < 2) return;
            double[] lat = new double[n], lon = new double[n];
            for (int i = 0; i < n; i++) {
                String t = tk[i].replace("[", "").replace("]", "");
                String[] xy = t.split(",");
                lon[i] = Double.parseDouble(xy[0].trim());           // guideLine order is lon,lat,0
                lat[i] = Double.parseDouble(xy[1].trim());
            }
            double m = 0;
            for (int i = 1; i < n; i++) m += haversine(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            publish(ctx, lat, lon, (float) m, 6);
        } catch (Throwable t) { HudLog.f("ADAS gl parse: " + t); }
    }

    private static double haversine(double la1, double lo1, double la2, double lo2) {
        double r = 6371000.0, dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ---- minimal protobuf wire encoder ------------------------------------------------------
    private static byte[] varint(int field, long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        vraw(o, ((long) field << 3));                          // tag, wire 0
        vraw(o, v); return o.toByteArray();
    }
    private static byte[] f64(int field, double v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        vraw(o, ((long) field << 3) | 1);                      // wire 1 (fixed64)
        long b = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) o.write((int) (b >>> (8 * i)) & 0xff);
        return o.toByteArray();
    }
    private static byte[] f32(int field, float v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        vraw(o, ((long) field << 3) | 5);                      // wire 5 (fixed32)
        int b = Float.floatToIntBits(v);
        for (int i = 0; i < 4; i++) o.write((b >>> (8 * i)) & 0xff);
        return o.toByteArray();
    }
    private static byte[] strf(int field, String s) {
        try { byte[] b = s.getBytes("UTF-8"); ByteArrayOutputStream o = new ByteArrayOutputStream();
              lenField(o, field, b); return o.toByteArray(); } catch (Throwable t) { return new byte[0]; }
    }
    private static void lenField(ByteArrayOutputStream o, int field, byte[] body) {
        try { vraw(o, ((long) field << 3) | 2); vraw(o, body.length); o.write(body); } catch (Throwable t) {}
    }
    private static void vraw(ByteArrayOutputStream o, long v) {
        while (true) { int b = (int) (v & 0x7f); v >>>= 7; if (v != 0) o.write(b | 0x80); else { o.write(b); break; } }
    }
    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try { for (byte[] p : parts) o.write(p); } catch (Throwable t) {}
        return o.toByteArray();
    }
}
