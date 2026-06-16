package com.zbyd.hudhook;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-process Yandex Weather fetch for the BYD HUD. The standalone ru.yandex.weatherplugin CANNOT run
 * re-signed (its passport SSO does a signature cert-check that rejects a foreign key), so instead the
 * modded Navi — which DOES run re-signed — fetches weather itself, in-process, reusing Yandex's own
 * embedded Weather key (intended use: inside a Yandex app). Uses the route position lat/lon already
 * extracted by HudEvents, hits the documented REST forecast, maps condition → BYD weatherType, and
 * publishes via HudSomeIp.pushWeather. Network runs on its own thread (never the caller's).
 */
public final class HudWeatherApi {

    private static final String HOST = "https://api.weather.yandex.ru/v2/forecast";
    private static final String KEY  = "PUT-YANDEX-WEATHER-KEY-HERE"  /* extract X-Yandex-API-Key from ru.yandex.weatherplugin */;  // Yandex Weather embedded key (in-process)
    private static final long MIN_INTERVAL_MS = 15 * 60 * 1000L;                 // refresh at most every 15 min

    private static volatile long sLastFetch;
    private static volatile int sLastType = -1;
    private static volatile int sCarRain = -1;     // BYD rain sensor (0-14), -1=unknown
    private static Handler sNet;

    /** Car rain sensor (0-14) corroborates/overrides the API weather: ground truth beats forecast. */
    public static void setCarRain(Context c, int level) {
        if (level == sCarRain) return;
        sCarRain = level;
        if (level > 0) {   // it IS raining here -> push rain immediately regardless of forecast
            int wt = level >= 8 ? 5 : 2;   // heavy -> storm(5), else rain(2)
            if (wt != sLastType) { sLastType = wt; try { HudSomeIp.pushWeather(c.getApplicationContext(), wt); } catch (Throwable t) {} HudLog.f("carRain=" + level + " -> weatherType=" + wt); }
        }
    }

    /** Called from HudEvents with the current route lat/lon; throttled + off-thread. */
    public static void update(Context c, final double lat, final double lon) {
        if (c == null || (lat == 0 && lon == 0)) return;
        if (sCarRain > 0) return;   // car sensor says raining -> trust it, skip API override
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastFetch < MIN_INTERVAL_MS) return;
        sLastFetch = now;
        final Context app = c.getApplicationContext();
        net().post(new Runnable() { @Override public void run() { fetch(app, lat, lon); } });
    }

    private static synchronized Handler net() {
        if (sNet == null) {
            HandlerThread t = new HandlerThread("hud-weather"); t.start();
            sNet = new Handler(t.getLooper());
        }
        return sNet;
    }

    private static void fetch(Context app, double lat, double lon) {
        HttpURLConnection cn = null;
        try {
            String u = HOST + "?lat=" + lat + "&lon=" + lon + "&limit=1&hours=false&extra=false";
            cn = (HttpURLConnection) new URL(u).openConnection();
            cn.setRequestProperty("X-Yandex-API-Key", KEY);
            cn.setConnectTimeout(8000); cn.setReadTimeout(8000);
            int code = cn.getResponseCode();
            if (code != 200) {
                HudLog.f("weatherApi HTTP " + code + " (key may be graphql-scoped)");
                return;
            }
            String body = read(cn.getInputStream());
            JSONObject fact = new JSONObject(body).optJSONObject("fact");
            if (fact == null) { HudLog.f("weatherApi: no fact"); return; }
            int temp = fact.optInt("temp", -1000);
            String cond = fact.optString("condition", null);
            int wt = weatherType(cond);
            HudLog.f("weatherApi temp=" + temp + " cond=" + cond + " -> weatherType=" + wt);
            if (wt != sLastType) { sLastType = wt; HudSomeIp.pushWeather(app, wt); }
        } catch (Throwable t) {
            HudLog.f("weatherApi fail: " + t);
        } finally { if (cn != null) try { cn.disconnect(); } catch (Throwable ignore) {} }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    /** Yandex /v2 condition → BYD weatherType (clear=0/cloud=1/rain=2/snow=3/fog=4/storm=5). */
    private static int weatherType(String cond) {
        String s = cond == null ? "" : cond.toLowerCase();
        if (s.contains("thunder") || s.contains("hail")) return 5;
        if (s.contains("snow") || s.contains("sleet")) return 3;
        if (s.contains("rain") || s.contains("drizzle")) return 2;
        if (s.contains("fog") || s.contains("mist") || s.contains("haze")) return 4;
        if (s.contains("cloud") || s.contains("overcast")) return 1;
        return 0; // clear / partly-clear
    }
}
