package com.zbyd.hudhook;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Client to the shell-uid autoservice helper (AutoHelper @zbyd_auto). Pulls BYD EV telemetry
 * (SOC / odometer / lifetime kWh from energydata.db) — which a normal app can't read — computes
 * consumption + estimated range, and issues car-control writes (climate/lights/windows, dev/fid
 * reversed by BYDMate) that the per-app BYDAUTO_*_SET signature wall would otherwise block. No-op
 * (back-off) when the helper isn't running.
 */
public final class HudCarClient {

    private static final String SOCK = "zbyd_auto";
    private static final double CAPACITY_KWH = 100.0;            // Denza N9 pack (configurable)
    private static final double DEFAULT_CONS = 18.0;            // kWh/100km until first delta

    private static volatile int sSoc = -1;
    private static volatile double sMileage, sTotalElec, sCons = DEFAULT_CONS, sRangeKm;
    private static double sPrevMileage, sPrevElec;
    private static volatile boolean sStarted, sAnnounced;
    private static Context sCtx;
    private static Handler sBg;

    public static int soc()       { return sSoc; }
    public static double rangeKm(){ return sRangeKm; }
    public static double consumption() { return sCons; }

    public static void start(Context c) {
        if (sStarted || c == null) return;
        sStarted = true; sCtx = c.getApplicationContext();
        HandlerThread t = new HandlerThread("hud-car"); t.start();
        sBg = new Handler(t.getLooper());
        sBg.post(POLL);
    }

    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            try { pollEv(); } catch (Throwable t) {}
            if (sBg != null) sBg.postDelayed(this, 30000L);     // EV refresh ~30 s
        }
    };

    private static void pollEv() {
        String r = req("EV");
        if (r == null || !r.startsWith("OK")) return;
        int soc = (int) val(r, "soc=");
        double mileage = val(r, "mileage="), elec = val(r, "total_elec=");
        if (soc >= 0) sSoc = soc;
        if (mileage > 0) sMileage = mileage;
        if (elec > 0) sTotalElec = elec;
        // rolling consumption from deltas (need movement + energy used)
        if (sPrevMileage > 0 && mileage > sPrevMileage + 0.5 && elec > sPrevElec) {
            double cons = (elec - sPrevElec) / (mileage - sPrevMileage) * 100.0;   // kWh/100km
            if (cons > 5 && cons < 60) sCons = 0.7 * sCons + 0.3 * cons;           // smoothed
        }
        if (mileage > 0) { sPrevMileage = mileage; sPrevElec = elec; }
        if (sSoc >= 0 && sCons > 0) sRangeKm = sSoc / 100.0 * CAPACITY_KWH / sCons * 100.0;
        HudLog.f("EV soc=" + sSoc + " range=" + (int) sRangeKm + "km cons=" + String.format(java.util.Locale.US, "%.1f", sCons) + " odo=" + (long) sMileage);
        journal();
        announceOnce();
    }

    /** Append an EV snapshot to a trip journal CSV (getFilesDir/ev_journal.csv) — trips/charges derivable. */
    private static void journal() {
        try {
            java.io.File f = new java.io.File(sCtx.getFilesDir(), "ev_journal.csv");
            boolean head = !f.exists();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            if (head) w.write("ts,soc,mileage,total_elec,range_km,cons\n");
            w.write(System.currentTimeMillis() + "," + sSoc + "," + sMileage + "," + sTotalElec
                    + "," + (int) sRangeKm + "," + String.format(java.util.Locale.US, "%.1f", sCons) + "\n");
            w.close();
        } catch (Throwable t) {}
    }

    /** One-time spoken range summary shortly after guidance starts (nav-guidance audio channel). */
    private static void announceOnce() {
        if (sAnnounced || sSoc < 0) return;
        sAnnounced = true;
        try { HudTts.say(sCtx, "Заряд " + sSoc + " процентов, запас хода " + (int) sRangeKm + " километров"); }
        catch (Throwable t) {}
    }

    // ---- car control (autoservice writes via the shell helper) ------------------------------
    public static int write(int dev, int fid, int value) {
        String r = req("W " + dev + " " + fid + " " + value);
        try { return r != null && r.startsWith("OK") ? Integer.parseInt(r.substring(3).trim()) : -1; }
        catch (Throwable t) { return -1; }
    }
    public static int read(int dev, int fid) {
        String r = req("R " + dev + " " + fid);
        try { return r != null && r.startsWith("OK") ? Integer.parseInt(r.substring(3).trim()) : -999; }
        catch (Throwable t) { return -999; }
    }

    // Convenience controls (BYDMate live-validated dev/fid — verify on N9 before trusting).
    public static void ac(boolean on)        { write(1000, on ? 501219352 : 501219364, on ? 0 : 1); }
    public static void acTemp(int celsius)   { write(1000, 501219368, Math.max(16, Math.min(30, celsius))); }
    public static void windowDriver(boolean open)    { write(1001, 1125122104, open ? 1 : 2); }
    public static void windowPassenger(boolean open) { write(1001, 1125122107, open ? 1 : 2); }
    public static void sunroof(int op)       { write(1001, 1125122056, op); }   // 1 open/2 close/4 stop
    public static void interiorLight(boolean on)     { write(1023, 1330643002, on ? 2 : 1); }
    public static void ambientLight(boolean on)      { write(1023, 1069547536, on ? 5 : 1); }
    public static void lockDoors(boolean lock)       { write(1001, 1276141590, lock ? 2 : 1); }

    // ---- socket plumbing --------------------------------------------------------------------
    private static volatile LocalSocket sSock;
    private static volatile OutputStream sOut;
    private static volatile BufferedReader sIn;
    private static long sLastTry;
    private static int sLog;

    private static synchronized String req(String cmd) {
        if (!connect()) return null;
        try {
            sOut.write((cmd + "\n").getBytes("UTF-8")); sOut.flush();
            return sIn.readLine();
        } catch (Throwable t) { close(); return null; }
    }

    private static boolean connect() {
        if (sOut != null) return true;
        long now = System.currentTimeMillis();
        if (now - sLastTry < 5000) return false;
        sLastTry = now;
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
            sSock = s; sOut = s.getOutputStream();
            sIn = new BufferedReader(new InputStreamReader(s.getInputStream()));
            HudLog.f("HudCarClient connected @" + SOCK);
            return true;
        } catch (Throwable t) { if (sLog++ < 3) HudLog.f("HudCarClient no helper @" + SOCK); return false; }
    }

    private static void close() {
        try { sSock.close(); } catch (Throwable e) {}
        sSock = null; sOut = null; sIn = null;
    }

    private static double val(String s, String key) {
        try {
            int i = s.indexOf(key); if (i < 0) return -1;
            int j = i + key.length(), k = j;
            while (k < s.length() && s.charAt(k) != ' ') k++;
            return Double.parseDouble(s.substring(j, k));
        } catch (Throwable t) { return -1; }
    }
}
