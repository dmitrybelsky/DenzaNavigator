package com.zbyd.hudpriv;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Privileged HUD/cluster agent — injected (via JDWP) into a debuggable BYD app that HOLDS the
 * signature perms BYDAUTO_INSTRUMENT_SET + BYDAUTO_SETTING_SET (com.byd.cameraautostudy). Running
 * inside that process, our framework BYDAuto* calls pass the native UID permission check that a
 * re-signed Yandex can't. The patched Yandex (uid != privileged) streams nav commands here over an
 * abstract LocalSocket; we replay them to the cluster + HUD setting devices.
 *
 * Entry: start() — called once by the JDWP injector; spawns the socket server and returns.
 * Protocol (newline-delimited text on LocalSocket "zbyd_hud_priv"):
 *   CLUSTER <turnType> <distM> [road]   -> instrument guidance (+ nav status=2 once)
 *   CAMERA  <camType> <distM> <roadCls> -> instrument camera advisory
 *   STATUS  <n>                         -> sendAutoNaviStatus(n)
 *   SETTING <methodName> <intVal>       -> BYDAutoSettingDevice.<methodName>(intVal)  (HUD layout/map)
 */
public final class HudPrivAgent {

    static final String SOCK = "zbyd_hud_priv";
    static volatile boolean started;

    private static Object instr, setting, body;
    private static Method mStatus, mSimple, mNext, mCamera;
    private static volatile boolean naviSet;
    private static Context ctx;

    /** JDWP-invoked entry. Idempotent. Returns a status string (visible to the injector). */
    public static String start() {
        if (started) return "already-running uid=" + android.os.Process.myUid();
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { try { serve(); } catch (Throwable e) { log("serve fail: " + e); } }
        }, "zbyd-hud-priv");
        t.setDaemon(true);
        t.start();
        return "started uid=" + android.os.Process.myUid();
    }

    // BYD HUD instrument FIDs (com.byd.feature.instrument.Instrument)
    private static final int FID_HUD_NAVIGATION_MAP_SET = 0x32B1102E;   // 1 = enable windshield map panel

    private static void serve() throws Exception {
        ctx = (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        resolveInstr();
        boolean mapOn = writeFidOn(instr, FID_HUD_NAVIGATION_MAP_SET, 1);  // enable HUD nav-map panel on startup
        Thread fb = new Thread(new Runnable() { @Override public void run() { fileBridge(); } }, "zbyd-filebridge");
        fb.setDaemon(true); fb.start();                                    // /sdcard channel for untrusted_app (Yandex)
        LocalServerSocket srv = new LocalServerSocket(SOCK);
        log("listening on @" + SOCK + " uid=" + android.os.Process.myUid() + " instr=" + (instr != null) + " hudMap=" + mapOn);
        while (true) {
            try {
                LocalSocket s = srv.accept();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) dispatch(line);
                try { s.close(); } catch (Throwable e) {}
            } catch (Throwable e) { log("accept fail: " + e); }
        }
    }

    private static void resolveInstr() {
        try {
            Class<?> k = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            instr = k.getMethod("getInstance", Context.class).invoke(null, ctx);
            mStatus = k.getMethod("sendAutoNaviStatus", int.class);
            mSimple = k.getMethod("sendSimpleGuidanceInfo", int.class, int.class);
            mNext   = k.getMethod("sendNextPathName", String.class);
            try { mCamera = k.getMethod("sendCameraGuidanceInfo", int.class, int.class, int.class); } catch (Throwable e) {}
        } catch (Throwable e) { log("instr resolve fail: " + e); }
    }

    private static synchronized Object setting() {
        if (setting != null) return setting;
        try {
            Class<?> k = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
            setting = k.getMethod("getInstance", Context.class).invoke(null, ctx);
        } catch (Throwable e) { log("setting resolve fail: " + e); }
        return setting;
    }

    private static synchronized Object body() {
        if (body != null) return body;
        try {
            Class<?> k = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            body = k.getMethod("getInstance", Context.class).invoke(null, ctx);
        } catch (Throwable e) { log("body resolve fail: " + e); }
        return body;
    }

    private static void dispatch(String line) {
        try {
            String[] p = line.trim().split("\\s+", 4);
            String cmd = p[0];
            if ("CLUSTER".equals(cmd) && instr != null) {
                int tt = Integer.parseInt(p[1]), d = Integer.parseInt(p[2]);
                if (!naviSet) { naviSet = true; try { mStatus.invoke(instr, 2); } catch (Throwable e) {} }
                int ret = (Integer) mSimple.invoke(instr, tt, d);
                if (p.length > 3 && mNext != null) try { mNext.invoke(instr, p[3]); } catch (Throwable e) {}
                logN("CLUSTER t=" + tt + " d=" + d + " road=" + (p.length > 3 ? p[3] : "") + " ret=" + ret);
            } else if ("CAMERA".equals(cmd) && mCamera != null) {
                mCamera.invoke(instr, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } else if ("STATUS".equals(cmd) && mStatus != null) {
                mStatus.invoke(instr, Integer.parseInt(p[1]));
            } else if ("SETTING".equals(cmd)) {                         // SETTING <method> [int args...]
                Object dev = setting();
                String[] q = line.trim().split("\\s+");
                if (dev != null && q.length >= 2) {
                    int n = q.length - 2;
                    Class<?>[] sig = new Class<?>[n]; Object[] av = new Object[n];
                    for (int i = 0; i < n; i++) { sig[i] = int.class; av[i] = Integer.parseInt(q[2 + i]); }
                    Method m = findMethod(dev.getClass(), q[1], sig);
                    Object ret = m.invoke(dev, av);
                    logN("SETTING " + q[1] + " " + java.util.Arrays.toString(av) + " ret=" + ret);
                }
            } else if ("FIDSET".equals(cmd)) {                          // FIDSET <hexOrDecFid> <val> — instrument device (1007)
                int fid = p[1].toLowerCase().startsWith("0x")
                        ? (int) Long.parseLong(p[1].substring(2), 16) : Integer.parseInt(p[1]);
                writeFidOn(instr, fid, Integer.parseInt(p[2]));
            } else if ("BODYFID".equals(cmd)) {                         // BODYFID <hexOrDecFid> <val> — bodywork device (1001): rear sunshade etc.
                int fid = p[1].toLowerCase().startsWith("0x")
                        ? (int) Long.parseLong(p[1].substring(2), 16) : Integer.parseInt(p[1]);
                writeFidOn(body(), fid, Integer.parseInt(p[2]));
            } else if ("BODY".equals(cmd)) {                            // BODY <method> [int args...]
                Object dev = body();                                    // windows/seats/doors/moonroof/trunk
                String[] q = line.trim().split("\\s+");
                if (dev != null && q.length >= 2) {
                    int n = q.length - 2;
                    Class<?>[] sig = new Class<?>[n]; Object[] args = new Object[n];
                    for (int i = 0; i < n; i++) { sig[i] = int.class; args[i] = Integer.parseInt(q[2 + i]); }
                    Method m = findMethod(dev.getClass(), q[1], sig);
                    Object ret = m.invoke(dev, args);
                    logN("BODY " + q[1] + " " + java.util.Arrays.toString(args) + " ret=" + ret);
                }
            }
        } catch (Throwable e) { log("dispatch fail [" + line + "]: " + e); }
    }

    /** Write a BYD instrument feature-id directly: BYDAutoInstrumentDevice.set(mDeviceType, fid, value).
     *  deviceType is read reflectively from the live device instance (no hardcoded constant). The agent's
     *  UID holds BYDAUTO_INSTRUMENT_SET so the native permission check passes. */
    private static final int DEVICE_TYPE_INSTRUMENT = 1007;   // BYDAutoInstrumentDevice.mDeviceType (from sources)

    /** Raw feature-id write on ANY BYDAuto device via its protected set(mDeviceType, fid, value).
     *  deviceType read reflectively from the live instance; the agent UID holds the device's *_SET perm
     *  so the native permission check passes. Validated on N9: rear sunshade fid 1276178472 on body (dt=1001). */
    private static boolean writeFidOn(Object dev, int fid, int val) {
        if (dev == null) return false;
        try {
            java.lang.reflect.Field fdt = findField(dev.getClass(), "mDeviceType");
            int dt = fdt != null ? fdt.getInt(dev) : DEVICE_TYPE_INSTRUMENT;
            Method set3 = findMethod(dev.getClass(), "set", int.class, int.class, int.class);
            Object ret = set3.invoke(dev, dt, fid, val);
            logN("FIDSET fid=0x" + Integer.toHexString(fid) + " dt=" + dt + " val=" + val + " ret=" + ret);
            return true;
        } catch (Throwable e) { log("writeFid 0x" + Integer.toHexString(fid) + " fail: " + e); return false; }
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass())
            try { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (Throwable e) {}
        return null;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            try { Method m = k.getDeclaredMethod(name, args); m.setAccessible(true); return m; }
            catch (Throwable e) {}
        throw new NoSuchMethodException(name);
    }

    private static int sLogN;
    static volatile String lastReply = "OK";
    private static void logN(String m) { lastReply = m; if (sLogN++ < 40) log(m); }

    /** Run one command line and return its reply (captured from logN). Used by the /sdcard file bridge. */
    private static String runAndReply(String line) { lastReply = "OK"; dispatch(line); return lastReply; }

    /** Cross-domain command channel for the re-signed Yandex (untrusted_app): the agent's abstract socket is
     *  SELinux-unreachable from untrusted_app, but /sdcard (mlstrustedobject, MLS-exempt) is shared and this
     *  app holds MANAGE_EXTERNAL_STORAGE. Yandex atomically renames cmd.tmp->cmd; we exec + write reply. */
    private static void fileBridge() {
        java.io.File dir = new java.io.File("/sdcard/zbyd");
        try { dir.mkdirs(); } catch (Throwable e) {}
        java.io.File cmd = new java.io.File(dir, "cmd"), rep = new java.io.File(dir, "reply"), repTmp = new java.io.File(dir, "reply.tmp");
        log("fileBridge start dir=" + dir + " writable=" + dir.canWrite());
        while (true) {
            try {
                if (cmd.exists()) {
                    String line = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(cmd))).readLine();
                    cmd.delete();
                    String reply = line != null ? runAndReply(line.trim()) : "ERR empty";
                    FileWriter w = new FileWriter(repTmp); w.write(reply + "\n"); w.close();
                    repTmp.renameTo(rep);                         // atomic publish
                }
                Thread.sleep(250);
            } catch (Throwable e) { log("fileBridge: " + e); try { Thread.sleep(1000); } catch (Throwable e2) {} }
        }
    }

    private static void log(String m) {
        try {
            String dir = ctx != null ? ctx.getFilesDir().getAbsolutePath() : "/data/local/tmp";
            FileWriter w = new FileWriter(dir + "/hudpriv.log", true);
            w.write(System.currentTimeMillis() + " " + m + "\n");
            w.close();
        } catch (Throwable e) {}
    }
}
