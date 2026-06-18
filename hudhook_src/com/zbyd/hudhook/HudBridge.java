package com.zbyd.hudhook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;

/**
 * Unified client to the privileged HudPrivAgent (injected into com.byd.cameraautostudy). The re-signed
 * Yandex (untrusted_app) can neither actuate car hardware itself (signature perm) nor reach the agent's
 * abstract socket (SELinux denies untrusted_app -> platform_app connectto), so commands cross the domain
 * barrier through a /sdcard directory queue: /sdcard is mlstrustedobject (MLS-exempt) and both sides hold
 * MANAGE_EXTERNAL_STORAGE (granted once via `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`).
 *
 * A client atomically publishes req/<id>.cmd (tmp -> rename); the agent executes it with its privileged
 * UID and writes res/<id>.res. send() is fire-and-forget (writes/cluster); call() waits for the reply
 * (reads). Per-process unique ids (pid_seq) avoid collisions; the agent reaps stale replies.
 */
public final class HudBridge {

    private static final File REQ = new File("/sdcard/zbyd/req");
    private static final File RES = new File("/sdcard/zbyd/res");
    private static final int PID = android.os.Process.myPid();
    private static int sSeq;
    private static volatile boolean sReady, sChecked;
    private static int sLog;

    private static synchronized boolean ready() {
        if (sChecked) return sReady;
        sChecked = true;
        try { REQ.mkdirs(); RES.mkdirs(); sReady = REQ.exists(); } catch (Throwable t) { sReady = false; }
        HudLog.f("HudBridge req=" + REQ + " ready=" + sReady);
        return sReady;
    }

    private static synchronized String newId() { return PID + "_" + (++sSeq); }

    /** Fire-and-forget command (actuation / cluster). */
    public static void send(String cmd) {
        if (ready()) write(newId(), cmd);
    }

    /** Request-reply command (reads). Returns the agent's reply line, or null on timeout. */
    public static String call(String cmd, long timeoutMs) {
        if (!ready()) return null;
        String id = newId();
        if (!write(id, cmd)) return null;
        File out = new File(RES, id + ".res");
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (out.exists()) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(out)));
                    String line = r.readLine(); r.close(); out.delete(); return line;
                } catch (Throwable t) { return null; }
            }
            try { Thread.sleep(40); } catch (Throwable t) { return null; }
        }
        out.delete();
        return null;
    }

    /** Parse the trailing "ret=<int>" the agent appends to replies; def on miss. */
    public static int ret(String reply, int def) {
        if (reply == null) return def;
        int i = reply.lastIndexOf("ret=");
        if (i < 0) return def;
        try { return Integer.parseInt(reply.substring(i + 4).trim().split("\\s+")[0]); } catch (Throwable t) { return def; }
    }

    private static boolean write(String id, String cmd) {
        try {
            File tmp = new File(REQ, id + ".tmp"), c = new File(REQ, id + ".cmd");
            FileWriter w = new FileWriter(tmp); w.write(cmd + "\n"); w.close();
            return tmp.renameTo(c);
        } catch (Throwable t) { if (sLog++ < 3) HudLog.f("HudBridge write fail: " + t); return false; }
    }
}
