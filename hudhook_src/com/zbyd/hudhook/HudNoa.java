package com.zbyd.hudhook;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EXPERIMENTAL — OFF BY DEFAULT (HudFlags.ADAS_NOA). "Yandex route -> Amap HD route through dense
 * waypoints -> native NOA hugs the Yandex path".
 *
 * The native L3/NOA stack validates HD link/lane IDs against an on-device HD map, so a SYNTHETIC HD route
 * is rejected. The legit path: hand the stock BYD map (launchermap/Amap) a destination + intermediate
 * WAYPOINTS sampled from the Yandex route via its navigation deep link; Amap then builds a REAL HD route
 * threading those waypoints (valid link IDs) and publishes it to the ADAS, which runs NOA along it. With
 * dense waypoints the Amap route hugs the Yandex polyline (where Amap has HD coverage).
 *
 * Format VERIFIED against the BYD producer NaviProxy.startNavigation (com.byd.nfvc) and consumer parser
 * launchermap k/k/c/z/m1.java (host "route", scheme "bydautomap", manifest-registered, exported MainActivity):
 *   bydautomap://route?sourceApplication=<pkg>&data={"coordinateType":<int>,
 *                            "destination":{"name":..,"latitude":..,"longitude":..},
 *                            "waypoints":[{"name":..,"latitude":..,"longitude":..}, ...]}
 * Producer builds it as raw concat + Uri.parse; we Uri.encode the JSON (parser getQueryParameter decodes) so
 * stray &/#/space in names can't break the URI. Keys "latitude"/"longitude" confirmed (TrackReportField).
 *
 * coordinateType consumer (m1.v): ==2 -> WGS84->GCJ02 add-offset, ==3 -> BD09, else -> NO transform (verbatim).
 * Coord.transformWGS84ToGCJ02 has an out-of-China guard (returns 0 offset when lon<72.004|>137.83 or lat<0.83|>55.83),
 * so in Russia 1 and 2 are identical (offset=0). We send 1 (no-transform) — Yandex WGS84 used verbatim, correct.
 *
 * ⚠️ Route geometry is still Amap's (snaps each waypoint to its nearest HD road). NOA does the camera/radar-
 * vetoed driving. Closed/empty road, driver supervising.
 */
public final class HudNoa {

    private static final String PKG = "com.byd.launchermap";
    private static final String SRC_APP = "com.yandex.zbyd";  // sourceApplication attribution (producer sets caller pkg)
    private static final int COORD_NATIVE = 1;          // coordinateType: !=2,!=3 -> no transform (Russia: GCJ02==WGS84)
    private static final int MAX_WAYPOINTS = 16;        // cap so the URI stays sane + Amap accepts it
    private static final long MIN_INTERVAL_MS = 1000;   // floor between sends (matches the 1s poll); change-gated below
    private static long sLast;
    private static String sSig;                          // last sent route signature — skip identical re-sends

    /**
     * Parse the AR guideLine "[[lon,lat,0],...]", sample dense waypoints, route Amap dest+waypoints.
     * Polled every ~1s. Each send makes Amap RE-PLAN, so we re-send only when the route actually changed
     * (deviation / Yandex reroute / passing a via) — detected by a coord-quantized signature. A stable route
     * fires once; a real change reaches Amap within ~1s. Avoids re-plan storms that would reset active NOA.
     */
    public static void followRoute(Context ctx, String gl, String dest) {
        if (ctx == null || gl == null || gl.length() < 8 || !HudFlags.on(ctx, HudFlags.ADAS_NOA)) return;
        long now = System.currentTimeMillis();
        if (now - sLast < MIN_INTERVAL_MS) return;               // rate floor (burst guard)
        try {
            java.util.ArrayList<double[]> pts = parse(gl);
            int n = pts.size(); if (n < 2) return;
            JSONObject data = new JSONObject();
            data.put("coordinateType", COORD_NATIVE);
            double[] end = pts.get(n - 1);
            data.put("destination", poi(dest == null ? "Destination" : dest, end[0], end[1]));
            JSONArray wp = new JSONArray();
            StringBuilder sig = new StringBuilder().append(q(end[0])).append(',').append(q(end[1]));
            int step = Math.max(1, (n - 1) / (MAX_WAYPOINTS + 1));   // evenly sample interior points
            for (int i = step; i < n - 1; i += step) {
                double[] p = pts.get(i);
                wp.put(poi("", p[0], p[1]));                          // empty via name -> stable signature
                sig.append(';').append(q(p[0])).append(',').append(q(p[1]));
                if (wp.length() >= MAX_WAYPOINTS) break;
            }
            data.put("waypoints", wp);
            String s = sig.toString();
            if (s.equals(sSig)) return;                          // route unchanged (within ~11m) -> don't re-plan
            String uri = "bydautomap://route?sourceApplication=" + Uri.encode(SRC_APP)
                       + "&data=" + Uri.encode(data.toString());
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            sLast = now; sSig = s;
            HudLog.f("NOA route dest=" + end[0] + "," + end[1] + " wp=" + wp.length());
        } catch (Throwable t) { HudLog.f("NOA followRoute: " + t); }
    }

    /** Quantize a coord to ~4 decimals (~11m) so signature is stable while the car stays in one bucket. */
    private static long q(double v) { return Math.round(v * 1e4); }

    private static JSONObject poi(String name, double lat, double lon) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name); o.put("latitude", lat); o.put("longitude", lon);
        return o;
    }

    /** "[[lon,lat,0],[lon,lat,0],...]" -> list of {lat,lon}. */
    private static java.util.ArrayList<double[]> parse(String gl) {
        java.util.ArrayList<double[]> out = new java.util.ArrayList<double[]>();
        String s = gl.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        for (String t : s.split("\\],\\[")) {
            try { String[] xy = t.replace("[", "").replace("]", "").split(",");
                  out.add(new double[]{ Double.parseDouble(xy[1].trim()), Double.parseDouble(xy[0].trim()) }); }  // lat,lon from lon,lat,0
            catch (Throwable e) {}
        }
        return out;
    }
}
