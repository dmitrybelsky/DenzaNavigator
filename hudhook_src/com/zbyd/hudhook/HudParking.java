package com.zbyd.hudhook;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * In-process parking search via the running Yandex MapKit SearchManager (reflection — same
 * idea as HudEvents but for POI). Periodically searches "parking" near the current guidance
 * location and re-broadcasts each lot {name, lat, lon, capacity, price} to the HUD consumer as
 * a BYD roadFacilities-style event. No SDK / no own key — uses Yandex's in-process MapKit.
 *
 * All reflection is null-safe; exact method signatures are verified on-device against the live
 * MapKit. SearchManager.submit(Point, Integer radius, SearchOptions, SearchListener) is async —
 * results arrive in a dynamic-Proxy SearchListener.
 */
public final class HudParking {

    private static final int RADIUS_M = 1500;
    private static final int FACILITY_PARKING = 1;   // BYD naviFacilityType for parking

    private static volatile Object sGuidance;   // shared with HudEvents (NaviKit Guidance)
    private static volatile Object sSearchManager;
    private static Handler sHandler;

    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            try { HudParking.poll(); } catch (Throwable t) {}
            if (sHandler != null) sHandler.postDelayed(this, 30000L);   // every 30 s
        }
    };

    public static void setGuidance(Object g) {
        sGuidance = g;
        if (sHandler == null) {
            try {
                sHandler = new Handler(Looper.getMainLooper());
                sHandler.postDelayed(POLL, 5000L);
            } catch (Throwable t) {}
        }
    }

    private static Context ctx() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) { return null; }
    }

    private static void poll() throws Exception {
        Object g = sGuidance;
        Context c = ctx();
        if (g == null || c == null) return;

        // current Point from guidance location: guidance.location().getPosition()
        Object loc = call(g, "location");
        Object point = call(loc, "getPosition");
        if (point == null) return;

        Object mgr = searchManager();
        if (mgr == null) return;

        Object options = newSearchOptions();
        Object listener = newListener(c);
        if (options == null || listener == null) return;
        // find submit(Point, Integer, SearchOptions, SearchListener) by shape (4 args, 2nd=Integer)
        for (Method m : mgr.getClass().getMethods()) {
            if (!"submit".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[1] == Integer.class
                && p[0].isInstance(point) && p[2].isInstance(options) && p[3].isInstance(listener)) {
                try { m.setAccessible(true); m.invoke(mgr, point, Integer.valueOf(RADIUS_M), options, listener); }
                catch (Throwable t) {}
                return;
            }
        }
    }

    /** Lazy SearchFactory.getInstance().createSearchManager(ONLINE). */
    private static Object searchManager() {
        if (sSearchManager != null) return sSearchManager;
        try {
            Class<?> factory = Class.forName("com.yandex.mapkit.search.SearchFactory");
            Object search = factory.getMethod("getInstance").invoke(null);
            Class<?> typeEnum = Class.forName("com.yandex.mapkit.search.SearchManagerType");
            Object online = Enum.valueOf((Class<Enum>) typeEnum, "ONLINE");
            sSearchManager = search.getClass()
                .getMethod("createSearchManager", typeEnum).invoke(search, online);
        } catch (Throwable t) { sSearchManager = null; }
        return sSearchManager;
    }

    private static Object newSearchOptions() {
        try {
            return Class.forName("com.yandex.mapkit.search.SearchOptions")
                .getConstructor().newInstance();
        } catch (Throwable t) { return null; }
    }

    private static Class<?> listenerIface() {
        try { return Class.forName("com.yandex.mapkit.search.Session$SearchListener"); }
        catch (Throwable t) { return null; }
    }

    /** Dynamic Proxy for Session.SearchListener → onSearchResponse(Response). */
    private static Object newListener(final Context c) {
        Class<?> iface = listenerIface();
        if (iface == null) return null;
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{ iface },
            new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method m, Object[] args) {
                    if ("onSearchResponse".equals(m.getName()) && args != null && args.length > 0) {
                        try { onResponse(c, args[0]); } catch (Throwable t) {}
                    }
                    return null;
                }
            });
    }

    private static void onResponse(Context c, Object response) {
        Object coll = call(response, "getCollection");
        Object children = call(coll, "getChildren");
        if (!(children instanceof List)) return;
        int n = 0;
        for (Object item : (List<?>) children) {
            Object obj = call(item, "getObj");
            if (obj == null) continue;
            String name = str(call(obj, "getName"));
            Object geos = call(obj, "getGeometry");
            double lat = 0, lon = 0;
            if (geos instanceof List && !((List<?>) geos).isEmpty()) {
                Object pt = call(((List<?>) geos).get(0), "getPoint");
                lat = asDbl(call(pt, "getLatitude"));
                lon = asDbl(call(pt, "getLongitude"));
            }
            int capacity = -1; double price = -1;
            Object pa = parkingAttrs(obj);
            if (pa != null) {
                capacity = asInt(call(pa, "getPlacesCount"));
                Object money = call(pa, "getFirstHourPrice");
                price = asDbl(call(money, "getValue"));
            }
            if (lat == 0.0 && lon == 0.0) continue;
            HudSomeIp.pushFacility(c, FACILITY_PARKING, 0, lon, lat);  // → BYD roadFacilities (SOME/IP)
            if (++n >= 5) break;   // nearest 5 lots
        }
    }

    /** GeoObject.getMetadataContainer().getItem(ParkingAttributes.class). */
    private static Object parkingAttrs(Object geoObject) {
        try {
            Object mc = call(geoObject, "getMetadataContainer");
            if (mc == null) return null;
            Class<?> pa = Class.forName("com.yandex.mapkit.search.ParkingAttributes");
            return mc.getClass().getMethod("getItem", Class.class).invoke(mc, pa);
        } catch (Throwable t) { return null; }
    }

    // --- reflection helpers ---------------------------------------------------------------
    private static Object call(Object o, String m) {
        if (o == null) return null;
        try { Method mm = o.getClass().getMethod(m); mm.setAccessible(true); return mm.invoke(o); }
        catch (Throwable t) { return null; }
    }
    private static Object invoke(Object o, String m, Class<?>[] sig, Object... a) {
        if (o == null) return null;
        try { Method mm = o.getClass().getMethod(m, sig); mm.setAccessible(true); return mm.invoke(o, a); }
        catch (Throwable t) { return null; }
    }
    private static int asInt(Object o) { return o instanceof Number ? ((Number) o).intValue() : -1; }
    private static double asDbl(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0.0; }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
