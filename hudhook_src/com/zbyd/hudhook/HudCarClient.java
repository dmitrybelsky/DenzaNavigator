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

    private static final String SOCK = "zbyd_auto";             // AutoHelper daemon (runs as com.byd.cameraautostudy uid)
    private static final double CAPACITY_KWH = 100.0;            // Denza N9 pack (configurable)
    private static final double DEFAULT_CONS = 18.0;            // kWh/100km until first delta

    // BYDAuto device classes (mDeviceType validated live on N9). High-level HAL via the perm-holding daemon.
    static final String CLS_AC    = "android.hardware.bydauto.ac.BYDAutoAcDevice";            // 1000
    static final String CLS_BODY  = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"; // 1001
    static final String CLS_LIGHT = "android.hardware.bydauto.light.BYDAutoLightDevice";       // 1004
    static final String CLS_INSTR = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"; // 1007
    static final String CLS_TYRE  = "android.hardware.bydauto.tyre.BYDAutoTyreDevice";          // 1016
    static final String CLS_LOCK  = "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice";  // 1041

    /** Call a high-level HAL setter/getter on the daemon: "HAL <class> <method> [args]" -> int ret (-999 fail). */
    static int hal(String cls, String method, int... args) {
        StringBuilder sb = new StringBuilder("HAL ").append(cls).append(' ').append(method);
        for (int a : args) sb.append(' ').append(a);
        String r = req(sb.toString());
        if (r == null || !r.startsWith("OK")) return -999;
        int i = r.indexOf("ret="); if (i < 0) return -999;
        try { return Integer.parseInt(r.substring(i + 4).trim().split("\\s+")[0]); } catch (Throwable t) { return -999; }
    }
    /** Raw feature-id write on a device class via the daemon: "DSET <class> <fid> <val>". */
    static int dset(String cls, int fid, int val) {
        String r = req("DSET " + cls + " " + fid + " " + val);
        if (r == null || !r.startsWith("OK")) return -999;
        int i = r.indexOf("ret="); if (i < 0) return -999;
        try { return Integer.parseInt(r.substring(i + 4).trim().split("\\s+")[0]); } catch (Throwable t) { return -999; }
    }
    /** Raw feature-id read on a device class via the daemon: "RGET <class> <fid>" -> int. */
    static int rget(String cls, int fid) {
        String r = req("RGET " + cls + " " + fid);
        try { return r != null && r.startsWith("OK") ? Integer.parseInt(r.substring(3).trim().split("\\s+")[0]) : -999; }
        catch (Throwable t) { return -999; }
    }
    private static String clsOf(int dev) {
        switch (dev) {
            case 1000: return CLS_AC;   case 1001: return CLS_BODY;  case 1004: return CLS_LIGHT;
            case 1007: return CLS_INSTR; case 1016: return CLS_TYRE; case 1041: return CLS_LOCK;
            default:   return CLS_BODY;
        }
    }

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
        int soc = hal(CLS_INSTR, "getBatteryPercent");          // live SOC % (instrument, perm-path)
        if (soc < 0 || soc > 100) return;                       // -999 fail / out of range
        int mi = hal(CLS_INSTR, "getCurrentJourneyDriveMileage"); // trip km (best-effort for journal)
        double mileage = mi > 0 ? mi : sMileage, elec = 0;
        if (soc >= 0) sSoc = soc;
        if (mileage > 0) sMileage = mileage;
        // rolling consumption from deltas (need movement + energy used)
        if (sPrevMileage > 0 && mileage > sPrevMileage + 0.5 && elec > sPrevElec) {
            double cons = (elec - sPrevElec) / (mileage - sPrevMileage) * 100.0;   // kWh/100km
            if (cons > 5 && cons < 60) sCons = 0.7 * sCons + 0.3 * cons;           // smoothed
        }
        boolean parked = sPrevMileage > 0 && Math.abs(mileage - sPrevMileage) < 0.1;
        // charge session: SOC rising while parked (no ignition signal -> use mileage-stationary)
        if (soc > sPrevSoc && sPrevSoc >= 0 && parked) {
            if (!sCharging) { sCharging = true; sChgStartTs = System.currentTimeMillis(); sChgStartSoc = sPrevSoc; sChgStartElec = elec; }
            sChgLastTs = System.currentTimeMillis();
        } else if (sCharging && (soc < sPrevSoc || System.currentTimeMillis() - sChgLastTs > 600000)) {
            logCharge(soc); sCharging = false;
        }
        if (soc >= 0) sPrevSoc = soc;
        if (mileage > 0) { sPrevMileage = mileage; sPrevElec = elec; }
        if (sSoc >= 0 && sCons > 0) sRangeKm = sSoc / 100.0 * CAPACITY_KWH / sCons * 100.0;
        pollTpms();
        HudLog.f("EV soc=" + sSoc + " range=" + (int) sRangeKm + "km cons=" + String.format(java.util.Locale.US, "%.1f", sCons) + " odo=" + (long) sMileage + " tpms=" + java.util.Arrays.toString(sTpms));
        journal();
        announceOnce();
    }

    // ---- TPMS (BYDAutoTyreDevice.getTyrePressureValue(area 1..4 = LF/RF/LR/RR), value 0-4094) ----
    private static final int[] sTpms = {0, 0, 0, 0};
    public static int[] tpms() { return sTpms; }
    private static long sTpmsTs;
    private static void pollTpms() {
        if (System.currentTimeMillis() - sTpmsTs < 60000) return; sTpmsTs = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) { int p = hal(CLS_TYRE, "getTyrePressureValue", i + 1); if (p > 50 && p < 600) sTpms[i] = p; }
    }

    // ---- charge journal -----------------------------------------------------------------------
    private static volatile boolean sCharging;
    private static long sChgStartTs, sChgLastTs;
    private static int sChgStartSoc = -1;
    private static double sChgStartElec;
    private static volatile int sPrevSoc = -1;
    private static void logCharge(int endSoc) {
        try {
            int added = endSoc - sChgStartSoc;
            if (added < 1) return;
            double kwh = added / 100.0 * CAPACITY_KWH;
            long durMin = (sChgLastTs - sChgStartTs) / 60000;
            java.io.File f = new java.io.File(sCtx.getFilesDir(), "charge_journal.csv");
            boolean head = !f.exists();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            if (head) w.write("start_ts,end_ts,soc_start,soc_end,kwh_added,duration_min\n");
            w.write(sChgStartTs + "," + sChgLastTs + "," + sChgStartSoc + "," + endSoc + ","
                    + String.format(java.util.Locale.US, "%.1f", kwh) + "," + durMin + "\n");
            w.close();
            HudLog.f("CHARGE +" + added + "% (" + String.format(java.util.Locale.US, "%.1f", kwh) + "kWh, " + durMin + "min)");
            try { HudTts.say(sCtx, "Зарядка завершена, добавлено " + added + " процентов"); } catch (Throwable t) {}
        } catch (Throwable t) {}
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

    // ---- car control (high-level HAL via the privileged daemon; raw fid via DSET/RGET) -------
    /** Raw fid write, dev number -> device class. Routes through the perm-holding daemon (actuates on N9). */
    public static int write(int dev, int fid, int value) { return dset(clsOf(dev), fid, value); }
    public static int read(int dev, int fid)             { return rget(clsOf(dev), fid); }

    // Convenience controls. HAL method args validated against N9 framework (BYDAutoBodyworkDevice etc).
    // Windows: setBodyWindowCtrlState(window 1=LF/2=RF/3=LR/4=RR, action 1=OPEN/2=CLOSE/3=STOP/4=HALF).
    public static void windowDriver(boolean open)    { hal(CLS_BODY, "setBodyWindowCtrlState", 1, open ? 1 : 2); }
    public static void windowPassenger(boolean open) { hal(CLS_BODY, "setBodyWindowCtrlState", 2, open ? 1 : 2); }
    public static void window(int win, boolean open) { hal(CLS_BODY, "setBodyWindowCtrlState", win, open ? 1 : 2); }
    /** Trunk/tailgate: setHetchDoorStatus(1=open/2=close). */
    public static void trunk(boolean open)           { hal(CLS_BODY, "setHetchDoorStatus", open ? 1 : 2); }
    // Panorama = electric sunshades on N9 (no opening glass). See HudPrivClient.sunshade()/rearSunshade().
    @Deprecated public static void sunroof(int op)   { hal(CLS_BODY, "voiceCtlMoonRoof", op); }

    // AC / lights / locks: high-level method or raw fid unverified on N9 -> routed through daemon, tune live.
    // AC on/off via control-mode (0=AUTO ~ on, 1=MANUAL); temp via setAcTemperature(zone,temp,src,unit=1 °C).
    public static void ac(boolean on)        { hal(CLS_AC, "setAcControlMode", 0, on ? 0 : 1); }
    public static void acTemp(int celsius)   { hal(CLS_AC, "setAcTemperature", 0, Math.max(17, Math.min(33, celsius)), 0, 1); }
    public static void interiorLight(boolean on) { dset(CLS_LIGHT, 1330643002, on ? 2 : 1); }   // fid Leopard3; verify on N9
    public static void ambientLight(boolean on)  { dset(CLS_LIGHT, 1069547536, on ? 5 : 1); }
    public static void lockDoors(boolean lock)   { dset(CLS_LOCK, 515647, lock ? 2 : 1); }       // SET_CAR_DOOR_LOCK_SET

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
