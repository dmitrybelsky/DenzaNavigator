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

    /** Route-following stub: feed the destination now (last polyline point). Dense via-points await a
     *  confirmed deep-link/ProtocolService format — Amap's NaviController.addWayPoint is internal. */
    public static void followRoute(Context ctx, double[] lat, double[] lon, String dest) {
        if (lat == null || lon == null || lat.length == 0) return;
        navigateTo(ctx, lat[lat.length - 1], lon[lon.length - 1], dest);   // destination only for now
    }
}
