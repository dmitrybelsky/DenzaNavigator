package com.zbyd.hudhook;

import android.content.Context;
import android.content.Intent;

/**
 * Re-broadcasts the in-process Yandex weather (CurrentWeatherDTO {icon, temp}) to the HUD
 * consumer as a BYD regionalAndWeather-style event. Fed by a smali invoke-static injected into
 * CurrentWeatherDTO.<init> — uses Yandex Navi's OWN in-process weather (its key, intended use).
 */
public final class HudWeather {

    private static final String PKG = "com.bilibili.bilithings";
    private static volatile int sLastTemp = Integer.MIN_VALUE;
    private static volatile String sLastIcon = "";

    /** Direct hook: w(icon, temp). */
    public static void w(String icon, int temp) { emit(icon, temp, null, Double.NaN, -1); }

    /**
     * Object hook: injected into the weather model's getTemp() (smali). Reflects temp/condition/
     * icon/feelsLike/humidity off the (obfuscated) Yandex weather POJO — field names vary by
     * version so we try getters then fields.
     */
    public static void fromWeather(Object o) {
        if (o == null) return;
        int temp = num(o, "getTemp", "temp", -1000).intValue();
        if (temp == -1000) return;
        String icon = txt(o, "getIcon", "icon");
        String cond = txt(o, "getCondition", "condition");
        double feels = num(o, "getFeelsLike", "feelsLike", Double.NaN).doubleValue();
        int hum = num(o, "getHumidity", "humidity", -1).intValue();
        emit(icon, temp, cond, feels, hum);
    }

    private static void emit(String icon, int temp, String cond, double feels, int hum) {
        if (temp == sLastTemp && icon != null && icon.equals(sLastIcon)) return;  // dedup
        sLastTemp = temp; sLastIcon = icon == null ? "" : icon;
        try {
            int wt = weatherType(cond, icon);
            android.util.Log.i("zbydHUD", "weather temp=" + temp + " cond=" + cond
                + " icon=" + icon + " -> weatherType=" + wt);   // observable proof (phone has no SOME/IP)
            Context c = (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
            if (c == null) return;
            HudSomeIp.pushWeather(c, wt);                         // → BYD regionalAndWeather (SOME/IP)
        } catch (Throwable t) {}
    }

    /** Yandex condition/icon string → BYD weatherType code (clear=0/cloud=1/rain=2/snow=3/fog=4/storm=5). */
    private static int weatherType(String cond, String icon) {
        String s = ((cond == null ? "" : cond) + " " + (icon == null ? "" : icon)).toLowerCase();
        if (s.contains("thunder") || s.contains("storm")) return 5;
        if (s.contains("snow") || s.contains("sleet")) return 3;
        if (s.contains("rain") || s.contains("drizzle") || s.contains("ovc_-ra")) return 2;
        if (s.contains("fog") || s.contains("mist") || s.contains("haze")) return 4;
        if (s.contains("ovc") || s.contains("cloud") || s.contains("bkn")) return 1;
        return 0; // clear / skc
    }

    private static Number num(Object o, String getter, String field, Number def) {
        Object v = call(o, getter); if (v == null) v = fld(o, field);
        return v instanceof Number ? (Number) v : def;
    }
    private static String txt(Object o, String getter, String field) {
        Object v = call(o, getter); if (v == null) v = fld(o, field);
        return v == null ? null : String.valueOf(v);
    }
    private static Object call(Object o, String m) {
        try { java.lang.reflect.Method mm = o.getClass().getMethod(m); mm.setAccessible(true); return mm.invoke(o); }
        catch (Throwable t) { return null; }
    }
    private static Object fld(Object o, String f) {
        try { java.lang.reflect.Field ff = o.getClass().getDeclaredField(f); ff.setAccessible(true); return ff.get(o); }
        catch (Throwable t) { return null; }
    }
}
