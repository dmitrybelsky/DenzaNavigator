package com.zbyd.hudhook;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflection bridge over the in-process NaviKit Guidance (stashed by the smali hook on
 * di/e.getGuidance). Polls the live DrivingRoute and re-broadcasts road incidents, the
 * upcoming maneuver (BYD turn-id), the forward route geometry (guideLine), and lane info
 * to the HUD consumer (com.bilibili.bilithings). No SDK / no API key — pure reflection.
 */
public final class HudEvents {

    private static final String PKG = "com.bilibili.bilithings";
    private static volatile Object sGuidance;
    private static Handler sHandler;
    private static int sLastType = -2;
    private static int sLastIcon = -2;

    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            try { HudEvents.poll(); } catch (Throwable t) {}
            if (sHandler != null) sHandler.postDelayed(this, 1000L);
        }
    };

    public static void setGuidance(Object obj) {
        HudLog.f("setGuidance obj=" + (obj == null ? "null" : obj.getClass().getName()));
        sGuidance = obj;
        try { HudParking.setGuidance(obj); } catch (Throwable t) {}   // one hook starts both
        // reverse channel: car SOME/IP+HAL fused position -> mock location -> Yandex (jam-resistant, lane)
        try { Context c = ctx(); if (c != null) { HudLocation.install(); HudLocationCar.start(c); HudSensors.start(c); } } catch (Throwable t) {}
        if (sHandler == null) {
            try {
                sHandler = new Handler(Looper.getMainLooper());
                sHandler.postDelayed(POLL, 1500L);
            } catch (Throwable t) {}
        }
    }

    private static Context ctx() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) { return null; }
    }

    private static int sPollLog;
    private static void poll() throws Exception {
        Object g = sGuidance;
        Context c = ctx();
        if (g == null || c == null) return;
        // DECISIVE injection check: what location is NaviKit actually using? Guidance.getLocation()
        // -> ClassifiedLocation.getLocation() -> Location.getPosition() -> Point lat/lon. If this tracks
        // our fed creeping-north coords (~55.96xx) the ExternalLocationReceiver injection WON over GPS.
        if (sPollLog % 5 == 0) {
            try {
                Object cl = call(g, "getLocation");
                Object loc = cl == null ? null : call(cl, "getLocation");
                Object pt = loc == null ? null : call(loc, "getPosition");
                if (pt != null) HudLog.f("NAVIKIT loc=" + asDbl(call(pt, "getLatitude")) + "," + asDbl(call(pt, "getLongitude")));
            } catch (Throwable t) {}
        }
        Object route = call(g, "route");
        boolean lg = sPollLog++ < 3;
        if (lg) HudLog.f("poll route=" + (route == null ? "null" : route.getClass().getName()));
        if (route == null) { sendIncident(c, -1, 0, 0); return; }

        Object pos = call(route, "getPosition");
        int curSeg = pos == null ? -1 : asInt(call(pos, "getSegmentIndex"));
        double curOff = pos == null ? 0 : asDbl(call(pos, "getSegmentPosition"));
        if (lg) HudLog.f("curSeg=" + curSeg + " off=" + curOff);

        try { pollIncidents(c, route, curSeg, curOff); } catch (Throwable t) { if (lg) HudLog.f("incidents ERR " + t); }
        try { pollManeuver(c, route, curSeg); } catch (Throwable t) { if (lg) HudLog.f("maneuver ERR " + t); }
        try { pollGeometry(c, route, curSeg); } catch (Throwable t) { if (lg) HudLog.f("geometry ERR " + t); }
        try { pollAhead(c, route, curSeg, "getTunnels", 0); } catch (Throwable t) { if (lg) HudLog.f("tunnels ERR " + t); }
        try { pollAhead(c, route, curSeg, "getTrafficLights", 1); } catch (Throwable t) { if (lg) HudLog.f("lights ERR " + t); }
        // weather: weatherplugin can't run re-signed (passport SSO), so fetch in-process here using
        // the route position (throttled to 15 min, off-thread) → BYD regionalAndWeather (SOME/IP)
        double[] ll = segLatLon(route, curSeg < 0 ? 0 : curSeg);
        try { if (lg) HudLog.f("weather call ll=" + ll[0] + "," + ll[1]);
              HudWeatherApi.update(c, ll[0], ll[1]); } catch (Throwable t) { if (lg) HudLog.f("weather ERR " + t); }
    }

    /**
     * Nearest upcoming route feature (kind 0=tunnel, 1=traffic-light) → publish to BYD SOME/IP.
     * Feature.getPosition() → PolylinePosition or Subpolyline(getBegin()/getEnd()); coords + distance
     * are read off the route geometry. Closes the BYD tunnelNotify / sdTrafficLight gaps.
     */
    private static void pollAhead(Context c, Object route, int curSeg, String getter, int kind) {
        Object list = call(route, getter);
        if (!(list instanceof List) || curSeg < 0) return;
        int bestSeg = Integer.MAX_VALUE; Object best = null;
        for (Object f : (List<?>) list) {
            Object pos = call(f, "getPosition");
            Object pp = call(pos, "getBegin");
            int seg = asInt(call(pp != null ? pp : pos, "getSegmentIndex"));
            if (seg >= curSeg && seg < bestSeg) { bestSeg = seg; best = f; }
        }
        if (best == null) return;
        int dist = distMeters(route, curSeg, bestSeg);
        double[] ll = segLatLon(route, bestSeg);
        if (kind == 0) {
            double[] end = segLatLon(route, bestSeg + 1);
            HudSomeIp.pushTunnel(c, dist, ll[0], ll[1], end[0], end[1]);
        } else {
            HudSomeIp.pushLight(c, ll[0], ll[1], dist);
            // light within ~300 m → enable the OCR countdown bridge (else idle)
            try { HudTlOcr.setLightAhead(dist > 0 && dist < 300); } catch (Throwable t) {}
        }
    }

    /** lat/lon of route geometry point at segment index. */
    private static double[] segLatLon(Object route, int seg) {
        Object pts = call(call(route, "getGeometry"), "getPoints");
        if (pts instanceof List) {
            List<?> g = (List<?>) pts;
            if (seg >= 0 && seg < g.size()) {
                Object p = g.get(seg);
                return new double[]{ asDbl(call(p, "getLatitude")), asDbl(call(p, "getLongitude")) };
            }
        }
        return new double[]{ 0, 0 };
    }

    // --- road incidents (accident/roadworks) + cameras (-> cluster instrument HAL) --------
    private static void pollIncidents(Context c, Object route, int curSeg, double curOff) {
        Object events = call(route, "getEvents");
        if (!(events instanceof List)) { sendIncident(c, -1, 0, 0); return; }
        int bestType = -1; double bLat = 0, bLon = 0; long best = Long.MAX_VALUE;
        int camType = -1, camSeg = -1; long camKey = Long.MAX_VALUE;   // nearest upcoming camera
        for (Object ev : (List<?>) events) {
            Object tags = call(ev, "getTags");
            int cam = classifyCamera(tags);
            int t = classifyIncident(tags);
            if (t < 0 && cam < 0) continue;
            Object pp = call(ev, "getPolylinePosition");
            Object loc = call(ev, "getLocation");
            double lat = asDbl(call(loc, "getLatitude"));
            double lon = asDbl(call(loc, "getLongitude"));
            int s = pp == null ? -1 : asInt(call(pp, "getSegmentIndex"));
            double o = pp == null ? 0 : asDbl(call(pp, "getSegmentPosition"));
            boolean ahead = pp == null || curSeg < 0 || !(s < curSeg || (s == curSeg && o <= curOff));
            if (!ahead) continue;
            long key = s < 0 ? 0 : (((long) s) << 20) + (long) (o * 1000.0);
            if (t >= 0 && key < best) { best = key; bestType = t; bLat = lat; bLon = lon; }
            if (cam >= 0 && key < camKey) { camKey = key; camType = cam; camSeg = s; }
        }
        sendIncident(c, bestType, bLat, bLon);
        if (camType >= 0) {   // feed the cluster: camera ahead via instrument HAL (TIER-1)
            int dist = distMeters(route, curSeg, camSeg < 0 ? curSeg : camSeg);
            try { HudInstrumentHal.pushCamera(camType, dist, 0); } catch (Throwable t2) {}
        }
    }

    /** Yandex EventTag -> BYD instrument CAMERA_TYPE (SPEED_LIMITED=1/TRAFFIC_LIGHT=2/INTERVAL_IN=5/BUS_LANE=8). */
    private static int classifyCamera(Object obj) {
        if (!(obj instanceof List)) return -1;
        for (Object o : (List<?>) obj) {
            String n = (o instanceof Enum ? ((Enum<?>) o).name() : String.valueOf(o)).toUpperCase();
            if (n.contains("SPEED_CONTROL")) return 1;          // speed camera
            if (n.contains("LANE_CONTROL")) return 8;           // bus/dedicated-lane camera
            if (n.contains("CROSS_ROAD")) return 2;             // junction/traffic-light camera
            if (n.contains("MOBILE_CONTROL") || n.contains("POLICE")) return 3;  // mobile/police -> peccancy
        }
        return -1;
    }

    // car turn-signal + ego-lane fed from HudLocationCar (VEHICLE_POSITION f31/f16/f20)
    private static volatile int sCarBlinker, sCarLaneId, sCarLaneNum;
    public static void onCarLane(int blinker, int laneId, int laneNum, double laneOff) {
        sCarBlinker = blinker; sCarLaneId = laneId; sCarLaneNum = laneNum;
    }

    // --- upcoming maneuver (annotation Action → BYD turn-id) ------------------------------
    private static void pollManeuver(Context c, Object route, int curSeg) {
        Object sections = call(route, "getSections");
        if (!(sections instanceof List)) return;
        for (Object sec : (List<?>) sections) {
            // section start segment (best-effort: geometry subpolyline begin)
            int secSeg = sectionStartSeg(sec);
            if (secSeg >= 0 && secSeg < curSeg) continue;          // already passed
            Object md = call(sec, "getMetadata");
            Object ann = call(md, "getAnnotation");
            Object action = call(ann, "getAction");
            if (action == null) continue;
            String name = action instanceof Enum ? ((Enum<?>) action).name()
                                                 : String.valueOf(action);
            int icon = bydIcon(name);
            String road = str(call(ann, "getToponym"));
            int dist = distMeters(route, curSeg, secSeg < 0 ? curSeg : secSeg);
            if (sPollLog <= 3) HudLog.f("MANEUVER action=" + name + " icon=" + icon + " road=" + road + " dist=" + dist);
            // hudbench-in-yandex: publish the windshield HUD frame (665) directly via SOME/IP
            HudSomeIp.pushHud(c, road, dist, "", icon);
            // TIER-1: also feed the CLUSTER via the instrument HAL (bypasses launchermap owner-gate)
            try { HudInstrumentHal.pushManeuver(icon, road, dist); } catch (Throwable t) {}
            try { HudTts.maneuver(c, icon, dist, road); } catch (Throwable t) {}   // native-voice prompt (opt-in)
            // lane confirmation: driver's turn-signal vs the upcoming maneuver direction
            if (sCarBlinker == 1 || sCarBlinker == 2) {
                boolean left = (icon == 2 || icon == 4 || icon == 6 || icon == 17);
                boolean right = (icon == 3 || icon == 5 || icon == 7 || icon == 18);
                boolean match = (sCarBlinker == 1 && left) || (sCarBlinker == 2 && right);
                if (sPollLog <= 3) HudLog.f("LANE blink=" + sCarBlinker + " maneuver=" + (left ? "L" : right ? "R" : "-") + " egoLane=" + sCarLaneId + "/" + sCarLaneNum + " -> " + (match ? "CONFIRMED" : "mismatch"));
            }
            return;                                                // nearest upcoming only
        }
    }

    /** Meters from current segment to the maneuver segment, summed over route geometry (haversine). */
    private static int distMeters(Object route, int from, int to) {
        if (to <= from) return 30;
        Object pts = call(call(route, "getGeometry"), "getPoints");
        if (!(pts instanceof List)) return (to - from) * 12;
        List<?> g = (List<?>) pts; int n = g.size();
        if (to >= n) to = n - 1;
        double m = 0;
        for (int i = from; i < to && i + 1 < n; i++) {
            Object a = g.get(i), b = g.get(i + 1);
            m += haversine(asDbl(call(a, "getLatitude")), asDbl(call(a, "getLongitude")),
                           asDbl(call(b, "getLatitude")), asDbl(call(b, "getLongitude")));
        }
        return (int) m;
    }

    private static double haversine(double la1, double lo1, double la2, double lo2) {
        if (la1 == 0 && lo1 == 0) return 0;
        double R = 6371000, p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = Math.toRadians(la2 - la1), dl = Math.toRadians(lo2 - lo1);
        double x = Math.sin(dp / 2) * Math.sin(dp / 2)
                 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
    }

    private static int sectionStartSeg(Object sec) {
        // DrivingSection.getGeometry() -> Subpolyline.getBegin() -> PolylinePosition
        Object geo = call(sec, "getGeometry");
        Object begin = call(geo, "getBegin");
        return begin == null ? -1 : asInt(call(begin, "getSegmentIndex"));
    }

    // --- forward route geometry (guideLine polyline) -------------------------------------
    private static void pollGeometry(Context c, Object route, int curSeg) {
        Object geo = call(route, "getGeometry");
        Object pts = call(geo, "getPoints");
        if (!(pts instanceof List)) return;
        List<?> list = (List<?>) pts;
        int n = list.size();
        if (n == 0) return;
        int from = curSeg < 0 ? 0 : Math.min(curSeg, n - 1);
        int to = Math.min(from + 12, n);                          // ~12 points ahead
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            Object p = list.get(i);
            double lat = asDbl(call(p, "getLatitude"));
            double lon = asDbl(call(p, "getLongitude"));
            if (sb.length() > 0) sb.append(';');
            sb.append(lat).append(',').append(lon);
        }
        try {
            Intent in = new Intent("ru.yandex.yandexnavi.HUD_GEOMETRY").setPackage(PKG);
            in.putExtra("poly", sb.toString());
            c.sendBroadcast(in);
        } catch (Throwable t) {}
    }

    // Yandex Action enum name -> BYD AutoNavi ICON_* (launchermap).
    private static int bydIcon(String a) {
        if (a == null) return 9;
        String s = a.toUpperCase();
        if (s.contains("UTURN") || s.contains("U_TURN")) return 8;
        boolean left = s.contains("LEFT"), right = s.contains("RIGHT");
        if (s.contains("ROUNDABOUT") || s.contains("CIRCLE"))
            return left ? 17 : (right ? 18 : 11);
        if (s.contains("FINISH") || s.contains("DESTINATION")) return 15;
        if (s.contains("FORK")) return left ? 4 : (right ? 5 : 9);
        if (s.contains("HARD") || s.contains("SHARP")) return left ? 6 : (right ? 7 : 9);
        if (s.contains("SLIGHT")) return left ? 4 : (right ? 5 : 9);
        if (left) return 2;
        if (right) return 3;
        return 9; // STRAIGHT / CONTINUE / DEPART
    }

    // LaneSign list -> "dir:active|dir:active|..."  dir 0=straight 1=left 3=right
    private static String encodeLanes(Object signs) {
        if (!(signs instanceof List)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object sign : (List<?>) signs) {
            Object lanes = call(sign, "getLanes");
            if (!(lanes instanceof List)) continue;
            for (Object lane : (List<?>) lanes) {
                Object dirs = call(lane, "getDirections");
                Object hi = call(lane, "getHighlightedDirection");
                int code = laneCode(dirs);
                int active = hi != null ? 1 : 0;
                if (sb.length() > 0) sb.append('|');
                sb.append(code).append(':').append(active);
            }
        }
        return sb.toString();
    }

    private static int laneCode(Object dirs) {
        if (!(dirs instanceof List)) return 0;
        for (Object d : (List<?>) dirs) {
            String n = d instanceof Enum ? ((Enum<?>) d).name() : String.valueOf(d);
            String s = n.toUpperCase();
            if (s.contains("LEFT")) return 1;
            if (s.contains("RIGHT")) return 3;
        }
        return 0;
    }

    private static int classifyIncident(Object obj) {
        if (!(obj instanceof List)) return -1;
        for (Object o : (List<?>) obj) {
            String n = o instanceof Enum ? ((Enum<?>) o).name() : String.valueOf(o);
            if ("ACCIDENT".equals(n)) return 1;
            if ("RECONSTRUCTION".equals(n) || "CLOSED".equals(n)) return 2;
        }
        return -1;
    }

    // --- broadcast helpers ----------------------------------------------------------------
    private static void sendIncident(Context c, int type, double lat, double lon) {
        if (type < 0 && sLastType < 0) return;
        sLastType = type;
        try {
            Intent in = new Intent("ru.yandex.yandexnavi.HUD_INCIDENT").setPackage(PKG);
            in.putExtra("type", type); in.putExtra("lat", lat); in.putExtra("lon", lon);
            c.sendBroadcast(in);
        } catch (Throwable t) {}
    }

    private static void sendManeuver(Context c, int icon, String action, String road, String lanes) {
        try {
            Intent in = new Intent("ru.yandex.yandexnavi.HUD_MANEUVER").setPackage(PKG);
            in.putExtra("icon", icon);          // BYD turn-id
            in.putExtra("action", action);      // raw Yandex Action name
            in.putExtra("road", road == null ? "" : road);
            in.putExtra("lanes", lanes == null ? "" : lanes);
            c.sendBroadcast(in);
            sLastIcon = icon;
        } catch (Throwable t) {}
    }

    private static Object call(Object obj, String m) {
        if (obj == null) return null;
        try {
            Method method = obj.getClass().getMethod(m);
            method.setAccessible(true);
            return method.invoke(obj);
        } catch (Throwable t) { return null; }
    }

    private static int asInt(Object o) { return o instanceof Number ? ((Number) o).intValue() : -1; }
    private static double asDbl(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0.0; }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
