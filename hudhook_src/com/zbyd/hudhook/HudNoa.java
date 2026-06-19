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
 * Deep-link format reversed from launchermap k/k/c/z/m1.java (host "route", param "data" = JSON):
 *   bydautomap://route?data={"coordinateType":<int>,
 *                            "destination":{"name":..,"latitude":..,"longitude":..},
 *                            "waypoints":[{"name":..,"latitude":..,"longitude":..}, ...]}
 *
 * ⚠️ Route geometry is still Amap's (snaps each waypoint to its nearest HD road). NOA does the camera/radar-
 * vetoed driving. coordinateType (WGS84 vs GCJ02) — verify live. Closed/empty road, driver supervising.
 */
public final class HudNoa {

    private static final String PKG = "com.byd.launchermap";
    private static final int COORD_WGS84 = 1;          // coordinateType — N9 outside China is WGS84 (verify live)
    private static final int MAX_WAYPOINTS = 16;        // cap so the URI stays sane + Amap accepts it
    private static long sLast;

    /** Parse the AR guideLine "[[lon,lat,0],...]", sample dense waypoints, and route Amap dest+waypoints. */
    public static void followRoute(Context ctx, String gl, String dest) {
        if (ctx == null || gl == null || gl.length() < 8 || !HudFlags.on(ctx, HudFlags.ADAS_NOA)) return;
        long now = System.currentTimeMillis();
        if (now - sLast < 15000) return; sLast = now;            // re-route only occasionally (Amap re-plans)
        try {
            java.util.ArrayList<double[]> pts = parse(gl);
            int n = pts.size(); if (n < 2) return;
            JSONObject data = new JSONObject();
            data.put("coordinateType", COORD_WGS84);
            double[] end = pts.get(n - 1);
            data.put("destination", poi(dest == null ? "Destination" : dest, end[0], end[1]));
            JSONArray wp = new JSONArray();
            int step = Math.max(1, (n - 1) / (MAX_WAYPOINTS + 1));   // evenly sample interior points
            for (int i = step; i < n - 1; i += step) {
                double[] p = pts.get(i);
                wp.put(poi("wp" + i, p[0], p[1]));
                if (wp.length() >= MAX_WAYPOINTS) break;
            }
            data.put("waypoints", wp);
            String uri = "bydautomap://route?data=" + Uri.encode(data.toString());
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            HudLog.f("NOA route dest=" + end[0] + "," + end[1] + " wp=" + wp.length());
        } catch (Throwable t) { HudLog.f("NOA followRoute: " + t); }
    }

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
