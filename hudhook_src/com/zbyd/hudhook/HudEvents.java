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
        try { Context c = ctx(); if (c != null) { HudLocation.install(); HudLocationCar.start(c); HudSensors.start(c); HudCarClient.start(c); HudAutomation.init(c); HudAutomation.onRouteStart(); } } catch (Throwable t) {}
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
        if (c != null) HudFlagReceiver.register(c);   // external flag/ADAS kill-switch (idempotent)
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
            try { HudAutomation.onTunnel(dist, dist >= 0 && dist < 40); } catch (Throwable t) {}   // entering tunnel
        } else {
            HudSomeIp.pushLight(c, ll[0], ll[1], dist);
            // light within ~300 m → enable the OCR countdown bridge (else idle)
            try { HudTlOcr.setLightAhead(dist > 0 && dist < 300); } catch (Throwable t) {}
        }
    }

    /** Forward route polyline from the current segment as a HUD AR guideLine "[[lon,lat,0],...]"
     *  (up to ~20 geometry points ≈ the road ahead) — drives the windshield AR arrows along the
     *  REAL Yandex route, not a synthetic stub. */
    private static String forwardGuideLine(Object route, int curSeg) {
        Object pts = call(call(route, "getGeometry"), "getPoints");
        if (!(pts instanceof List)) return null;
        List<?> g = (List<?>) pts; int n = g.size();
        if (curSeg < 0) curSeg = 0;
        int end = Math.min(n, curSeg + 20);
        if (end - curSeg < 2) return null;
        StringBuilder b = new StringBuilder("[");
        for (int i = curSeg; i < end; i++) {
            Object p = g.get(i);
            double lat = asDbl(call(p, "getLatitude")), lon = asDbl(call(p, "getLongitude"));
            b.append("[").append(lon).append(",").append(lat).append(",0]");
            if (i < end - 1) b.append(",");
        }
        return b.append("]").toString();
    }

    /** Whole remaining route geometry curSeg..end (NO 20-pt cap) — for NOA: dest=last pt, via=sampled. */
    private static String fullGuideLine(Object route, int curSeg) {
        Object pts = call(call(route, "getGeometry"), "getPoints");
        if (!(pts instanceof List)) return null;
        List<?> g = (List<?>) pts; int n = g.size();
        if (curSeg < 0) curSeg = 0;
        if (n - curSeg < 2) return null;
        StringBuilder b = new StringBuilder("[");
        for (int i = curSeg; i < n; i++) {
            Object p = g.get(i);
            double lat = asDbl(call(p, "getLatitude")), lon = asDbl(call(p, "getLongitude"));
            b.append("[").append(lon).append(",").append(lat).append(",0]");
            if (i < n - 1) b.append(",");
        }
        return b.append("]").toString();
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

    /** Last route geometry point = destination (lat,lon), or null. */
    private static double[] destLatLon(Object route) {
        Object pts = call(call(route, "getGeometry"), "getPoints");
        if (pts instanceof List && !((List<?>) pts).isEmpty()) {
            Object p = ((List<?>) pts).get(((List<?>) pts).size() - 1);
            double la = asDbl(call(p, "getLatitude")), lo = asDbl(call(p, "getLongitude"));
            if (la != 0 || lo != 0) return new double[]{ la, lo };
        }
        return null;
    }

    /** True while NaviKit guidance is active (a route is set) — gates the map-capture push. */
    public static boolean guidanceActive() { return sGuidance != null; }

    // current road speed limit (km/h) — fed by the SpeedLimitView.setSpeedLimit hook, sent to HUD f11/f15
    private static volatile int sSpeedLimit;
    public static void onSpeedLimit(String s) {
        try {
            if (s == null) { sSpeedLimit = 0; return; }
            String d = s.replaceAll("[^0-9]", "");
            sSpeedLimit = d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (Throwable t) { sSpeedLimit = 0; }
    }

    // Yandex AnnotationLanes -> HUD f29 "back,front|" per lane (+count). front=back on the recommended
    // (highlighted) lane else 0 — the format the BYD HUD lane bars consume (reference PlatformHudImpl).
    private static String buildLanes(Object ann, int[] countOut) {
        try {
            Object al = call(ann, "getAnnotationLanes");
            Object lanes = al == null ? null : call(al, "getLanes");
            if (!(lanes instanceof List)) return "";
            List<?> ls = (List<?>) lanes;
            if (ls.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (Object lane : ls) {
                Object hi = call(lane, "getHighlightedDirection");
                boolean active = hi != null;
                int code;
                if (active) code = laneCode(hi);
                else {
                    Object dirs = call(lane, "getDirections");
                    Object d0 = (dirs instanceof List && !((List<?>) dirs).isEmpty()) ? ((List<?>) dirs).get(0) : null;
                    code = laneCode(d0);
                }
                sb.append(code).append(",").append(active ? code : 0).append("|");
            }
            countOut[0] = ls.size();
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    /** Yandex LaneDirection enum name -> reference autonavi lane code: 0=straight, 1=left, 3=right. */
    private static int laneCode(Object dir) {
        if (dir == null) return 0;
        String n = (dir instanceof Enum ? ((Enum<?>) dir).name() : String.valueOf(dir)).toUpperCase();
        if (n.startsWith("LEFT")) return 1;
        if (n.startsWith("RIGHT")) return 3;
        return 0;   // STRAIGHT_AHEAD / UNKNOWN_DIRECTION
    }

    /** ETA = [remainingSeconds, arrivalEpochMs]; remaining time ≈ total route time * remDist/totalDist. */
    private static long[] computeEta(Object route, int curSeg) {
        try {
            Object w = call(call(route, "getMetadata"), "getWeight");
            double totalSec = asDbl(call(call(w, "getTimeWithTraffic"), "getValue"));
            double totalDist = asDbl(call(call(w, "getDistance"), "getValue"));
            if (totalSec <= 0 || totalDist <= 0) return new long[]{0, 0};
            int remDist = remainingMeters(route, curSeg);
            double frac = Math.min(1.0, remDist / totalDist);
            long remSec = (long) (totalSec * frac);
            return new long[]{ remSec, System.currentTimeMillis() + remSec * 1000L };
        } catch (Throwable t) { return new long[]{0, 0}; }
    }

    /** Meters from current segment to route end (haversine over geometry). */
    private static int remainingMeters(Object route, int curSeg) {
        Object pts = call(call(route, "getGeometry"), "getPoints");
        if (!(pts instanceof List)) return 0;
        List<?> g = (List<?>) pts; int n = g.size();
        if (curSeg < 0) curSeg = 0;
        double m = 0;
        for (int i = curSeg; i + 1 < n; i++)
            m += haversine(asDbl(call(g.get(i), "getLatitude")), asDbl(call(g.get(i), "getLongitude")),
                           asDbl(call(g.get(i + 1), "getLatitude")), asDbl(call(g.get(i + 1), "getLongitude")));
        return (int) m;
    }

    // Push remaining time/distance to the cluster's native NAVI fields (via the privileged agent).
    // FIDs: ESTIMATED_TIME 0x4C212010 (min), ESTIMATED_MILEAGE 0x4C212020 (km); TRIP hour/min/mileage.
    private static int sClusterTick;
    private static void sendClusterNav(int remainSec, int remainM) {
        if (remainSec <= 0) return;
        if ((sClusterTick++ & 1) != 0) return;                 // ~every other poll (~2s)
        int min = remainSec / 60, km = (remainM + 500) / 1000;
        HudPrivClient.fidSet("0x4C212010", min);               // NAVI_ESTIMATED_TIME (minutes)
        HudPrivClient.fidSet("0x4C212020", km);                // NAVI_ESTIMATED_MILEAGE (km)
        HudPrivClient.fidSet("0x43F02010", remainSec / 3600);  // NAVI_TRIP_INFO_HOUR
        HudPrivClient.fidSet("0x43F02018", min % 60);          // NAVI_TRIP_INFO_MINUTE
        HudPrivClient.fidSet("0x43F02028", km);                // NAVI_TRIP_INFO_MILEAGE
    }

    private static String clock(long epochMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(epochMs);
        return String.format(java.util.Locale.US, "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
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
            String road = roadName(ann);   // mapkit Annotation has NO getToponym(); use getDescriptionText/phrase
            int dist = distMeters(route, curSeg, secSeg < 0 ? curSeg : secSeg);
            if (sPollLog <= 3) HudLog.f("MANEUVER action=" + name + " icon=" + icon + " road=" + road + " dist=" + dist);
            // hudbench-in-yandex: publish the windshield HUD frame (665) directly via SOME/IP —
            // real live position + the actual Yandex route polyline ahead (AR arrows on the road).
            double[] here = segLatLon(route, curSeg < 0 ? 0 : curSeg);
            String gl = forwardGuideLine(route, curSeg < 0 ? 0 : curSeg);
            int[] laneCount = new int[1];
            String laneStr = buildLanes(ann, laneCount);                  // f5/f29 lane guidance
            long[] etaOut = computeEta(route, curSeg);                    // [remainSec, arrivalEpochMs]
            String etaClock = etaOut[0] > 0 ? clock(etaOut[1]) : "";      // f26 arrival HH:MM
            int rng = (int) HudCarClient.rangeKm();                       // EV range -> append to ETA slot (repurpose test)
            if (rng > 0 && etaClock.length() > 0) etaClock = etaClock + " " + rng + "km";
            HudSomeIp.pushHud(c, road, dist, etaClock, icon, here[0], here[1], gl,
                              (int) etaOut[0], laneStr, laneCount[0], sSpeedLimit);
            sendClusterNav((int) etaOut[0], remainingMeters(route, curSeg < 0 ? 0 : curSeg));
            try { HudAdasRoute.publishGl(c, gl); } catch (Throwable t) {}   // EXPERIMENTAL, off unless HudFlags.ADAS_ROUTE
            // EXPERIMENTAL NOA: route Amap to the FINAL destination + via-points sampled across the WHOLE
            // remaining route (full geometry, not the 20-pt AR window) so Amap's HD route hugs the Yandex path.
            try { HudNoa.followRoute(c, fullGuideLine(route, curSeg < 0 ? 0 : curSeg), road); } catch (Throwable t) {}
            try { HudAutomation.onManeuver(icon, dist); HudAutomation.onRouteProgress(remainingMeters(route, curSeg < 0 ? 0 : curSeg)); } catch (Throwable t) {}
            // TIER-1: also feed the CLUSTER via the instrument HAL (bypasses launchermap owner-gate)
            try { HudInstrumentHal.pushManeuver(icon, road, dist); } catch (Throwable t) {}
            try { HudInstrumentHal.pushLanes(laneStr, laneCount[0], dist); } catch (Throwable t) {}  // 5.1 framework-FID lanes
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

    /** Road/maneuver name from a mapkit driving Annotation. The public API has NO getToponym() — it exposes
     *  getDescriptionText():String (human-readable, includes the road) and getToponymPhrase():List (tokens).
     *  Try the clean phrase first, fall back to the description text. */
    private static String roadName(Object ann) {
        String d = str(call(ann, "getDescriptionText"));    // reliable String: road/maneuver text
        if (d != null && d.length() > 0) return d;
        try {                                                // fallback: join phrase tokens that are Strings
            Object ph = call(ann, "getToponymPhrase");
            if (ph instanceof List) {
                StringBuilder b = new StringBuilder();
                for (Object p : (List<?>) ph) if (p instanceof String) { if (b.length() > 0) b.append(' '); b.append((String) p); }
                if (b.length() > 0) return b.toString();
            }
        } catch (Throwable t) {}
        return d;
    }
}
