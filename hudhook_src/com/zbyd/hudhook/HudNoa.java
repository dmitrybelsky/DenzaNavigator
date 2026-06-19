package com.zbyd.hudhook;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * EXPERIMENTAL — OFF BY DEFAULT (HudFlags.ADAS_ROUTE). "Yandex destination -> Amap HD route -> native NOA".
 *
 * The native L3/NOA stack validates HD link/lane IDs against an on-device HD map (in firmware), so a
 * SYNTHETIC route published on NAVI_HD_LINK2 is rejected (see HudAdasRoute, which only feeds the L2 SD
 * topic). The legit bypass: don't synthesize HD — hand the DESTINATION (and, when the via-point format is
 * confirmed, intermediate waypoints) to the stock BYD map (launchermap/Amap) via its navigation deep link.
 * Amap then builds a real HD route with valid link IDs and publishes it to the ADAS, which runs NOA along
 * it. With dense Yandex waypoints the Amap route hugs the Yandex path (only where Amap has HD coverage).
 *
 * ⚠️ The route GEOMETRY is Amap's (it snaps each point to its nearest HD road), not Yandex's exact polyline.
 * NOA still does the camera/radar-vetoed driving. Closed/empty road, driver supervising. The exact deep-link
 * param names are obfuscated in launchermap — we try several URI forms; verify which one navigates on the car.
 */
public final class HudNoa {

    private static final String PKG = "com.byd.launchermap";
    private static long sLast;

    /** Hand a destination to Amap so it routes + (on a NOA-eligible road) the native ADAS runs NOA there.
     *  launchermap's MainActivity accepts bydautomap://<host> (host "route" confirmed in the manifest); the
     *  param names are obfuscated in the multi-layer dispatcher, so we try the AutoSDK/Amap-standard keys —
     *  verify which navigates on the car. */
    public static void navigateTo(Context ctx, double lat, double lon, String name) {
        if (ctx == null || !HudFlags.on(ctx, HudFlags.ADAS_NOA)) return;
        long now = System.currentTimeMillis();
        if (now - sLast < 10000) return; sLast = now;            // don't re-fire nav constantly
        String nm = name == null ? "" : Uri.encode(name);
        String[] uris = {
            "bydautomap://route?dlat=" + lat + "&dlon=" + lon + "&dname=" + nm + "&dev=0&t=0&sourceApplication=zbyd",
            "bydautomap://route?lat=" + lat + "&lon=" + lon + "&poiName=" + nm + "&dev=0&sourceApplication=zbyd",
            "amapuri://route/plan/?dlat=" + lat + "&dlon=" + lon + "&dname=" + nm + "&dev=0&t=0&sourceApplication=zbyd",
            "androidamap://navi?lat=" + lat + "&lon=" + lon + "&name=" + nm + "&dev=0&style=2",
        };
        for (String u : uris) {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(PKG)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                HudLog.f("NOA navigateTo " + lat + "," + lon + " via " + u.substring(0, Math.min(20, u.length())));
                return;                                          // first that launches wins
            } catch (Throwable t) { /* try next form */ }
        }
        HudLog.f("NOA navigateTo: no launchermap nav URI accepted");
    }

    /** Parse the AR guideLine "[[lon,lat,0],...]" and route Amap to its END (destination) — the native ADAS
     *  then runs NOA along Amap's HD route to that point. Off unless HudFlags.ADAS_NOA.
     *  NOTE: exact route-hugging via intermediate waypoints is NOT cleanly reachable — the obvious protocol
     *  (ProtocolService 30406 NaviOpera) turned out to be navigation *operations* on the active task, not
     *  navigate-to-coords; and the bydautomap://route deep link's via-param format is obfuscated. So we feed
     *  only the destination; Amap plans its own HD route to it. */
    public static void followRoute(Context ctx, String gl, String dest) {
        if (ctx == null || gl == null || gl.length() < 8 || !HudFlags.on(ctx, HudFlags.ADAS_NOA)) return;
        try {
            String s = gl.trim();
            if (s.startsWith("[")) s = s.substring(1);
            if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
            String[] tk = s.split("\\],\\[");
            double[] end = ll(tk[tk.length - 1]);
            if (end != null) navigateTo(ctx, end[0], end[1], dest);
        } catch (Throwable t) { HudLog.f("NOA followRoute: " + t); }
    }

    private static double[] ll(String t) {
        try { String[] xy = t.replace("[", "").replace("]", "").split(",");
              return new double[]{ Double.parseDouble(xy[1].trim()), Double.parseDouble(xy[0].trim()) }; }  // [lat,lon] from lon,lat,0
        catch (Throwable e) { return null; }
    }
}
