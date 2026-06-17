package com.zbyd.autohelper;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;

/**
 * Shell-uid car bridge (BYDMate-style, our own). Started via `app_process` from adb shell so it runs
 * as uid 2000 (shell) — a trusted uid the BYD "autoservice" system daemon accepts, which a re-signed
 * app's uid cannot. Gives non-root read/write of EVERY car feature-id (climate/lights/windows/etc),
 * bypassing the per-app BYDAUTO_*_SET signature wall. Also serves the BYD energydata SQLite (SOC /
 * mileage / total_elec) which shell can read but normal apps can't. Clients (patched Yandex,
 * HudCarClient) connect to the abstract LocalSocket "zbyd_auto".
 *
 * autoservice transact (reversed from BYDMate HelperDaemon.autoserviceTransact):
 *   write: transact(6, [interfaceToken(desc), dev, fid, value]) ; read: transact(READ_TX, [desc, dev, fid])
 * desc = autoservice.getInterfaceDescriptor() at runtime. WRITE tx=6 confirmed; READ tx best-guess 5
 * (override via arg0) — validate on N9.
 *
 * Protocol (newline text on @zbyd_auto):
 *   R <dev> <fid>          -> "OK <int>"        autoservice read
 *   W <dev> <fid> <value>  -> "OK <status>"     autoservice write (tx=6)
 *   EV                     -> "OK soc=.. mileage=.. total_elec=.."   from energydata.db
 */
public final class AutoHelper {

    private static final String SOCK = "zbyd_auto";
    private static final int TX_WRITE = 6;
    private static int TX_READ = 5;                                   // override: app_process ... AutoHelper <readTx>
    private static final String ENERGY_DB = "/storage/emulated/0/energydata/energydata.db";

    private static IBinder svc;
    private static String iface;

    public static void main(String[] args) {
        try {
            if (!connect()) { System.out.println("ERR no autoservice"); return; }
            // one-shot test mode: `app_process ... AutoHelper once <cmd...>` -> run once, print, exit
            if (args != null && args.length >= 2 && "once".equals(args[0])) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) { if (i > 1) sb.append(' '); sb.append(args[i]); }
                System.out.println(dispatch(sb.toString()));
                System.out.flush();
                try { Thread.sleep(1200); } catch (Throwable e) {}   // let oneway binder write flush
                Runtime.getRuntime().halt(0);            // looper/binder threads keep JVM alive; force-exit
                return;
            }
            if (args != null && args.length > 0) try { TX_READ = Integer.parseInt(args[0]); } catch (Throwable e) {}
            LocalServerSocket srv = new LocalServerSocket(SOCK);
            log("listening @" + SOCK);
            while (true) {
                try { handle(srv.accept()); } catch (Throwable t) { log("accept: " + t); }
            }
        } catch (Throwable t) { log("main fail: " + t); System.out.println("ERR " + t); }
    }

    private static boolean connect() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method get = sm.getMethod("getService", String.class);
        svc = (IBinder) get.invoke(null, "autoservice");
        if (svc == null) return false;
        iface = svc.getInterfaceDescriptor();
        log("autoservice ok iface=" + iface + " uid=" + android.os.Process.myUid() + " readTx=" + TX_READ);
        return true;
    }

    private static void handle(LocalSocket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
            String line;
            while ((line = r.readLine()) != null) { w.write(dispatch(line.trim())); w.write("\n"); w.flush(); }
        } catch (Throwable t) { /* client gone */ }
        finally { try { s.close(); } catch (Throwable e) {} }
    }

    private static String dispatch(String line) {
        try {
            String[] p = line.split("\\s+");
            if ("R".equals(p[0]))  return "OK " + transact(TX_READ,  Integer.parseInt(p[1]), Integer.parseInt(p[2]), 0, false);
            if ("W".equals(p[0]))  return "OK " + transact(TX_WRITE, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), true);
            if ("RX".equals(p[0]))  return "OK " + transact(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), 0, false);
            if ("WX".equals(p[0]))  return "OK " + transact(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), true);
            if ("WA".equals(p[0])) {                                     // WA <tx> <dev> <fid> <value> — setIntArray layout
                int tx = Integer.parseInt(p[1]), dev = Integer.parseInt(p[2]), fid = Integer.parseInt(p[3]), v = Integer.parseInt(p[4]);
                Parcel d = Parcel.obtain(), rp = Parcel.obtain();
                try { d.writeInterfaceToken(iface); d.writeInt(dev);
                    d.writeInt(1); d.writeInt(fid); d.writeInt(1); d.writeInt(v);   // int[]{fid}, int[]{value}
                    svc.transact(tx, d, rp, 0); return "OK " + (rp.dataAvail() >= 4 ? rp.readInt() : -999);
                } finally { rp.recycle(); d.recycle(); }
            }
            if ("SEND".equals(p[0])) {                                   // SEND <abstractSocket> <message...>
                String msg = line.substring(line.indexOf(p[2]));
                android.net.LocalSocket ls = new android.net.LocalSocket();
                ls.connect(new android.net.LocalSocketAddress(p[1], android.net.LocalSocketAddress.Namespace.ABSTRACT));
                ls.getOutputStream().write((msg + "\n").getBytes("UTF-8")); ls.getOutputStream().flush();
                try { ls.close(); } catch (Throwable e) {}
                return "OK sent to @" + p[1];
            }
            if ("HAL".equals(p[0])) {                                    // HAL <fqDeviceClass> <method> [int args...]
                Context c = ctx();
                Class<?> k = Class.forName(p[1]);
                Object dev = k.getMethod("getInstance", Context.class).invoke(null, c);
                int n = p.length - 3;
                Class<?>[] sig = new Class<?>[n]; Object[] av = new Object[n];
                for (int i = 0; i < n; i++) { sig[i] = int.class; av[i] = Integer.parseInt(p[3 + i]); }
                java.lang.reflect.Method m = null;
                for (Class<?> cc = k; cc != null && m == null; cc = cc.getSuperclass())
                    try { m = cc.getDeclaredMethod(p[2], sig); } catch (Throwable e) {}
                if (m == null) return "ERR no method " + p[2];
                m.setAccessible(true);
                return "OK uid=" + android.os.Process.myUid() + " ret=" + m.invoke(dev, av);
            }
            if ("DSET".equals(p[0])) {                                   // DSET <fqDeviceClass> <fid> <value> — raw fid via protected set(dev,fid,val) w/ HAL perm
                Context c = ctx();
                Class<?> k = Class.forName(p[1]);
                Object dev = k.getMethod("getInstance", Context.class).invoke(null, c);
                int devType = 0;
                for (Class<?> cc = k; cc != null; cc = cc.getSuperclass())
                    try { java.lang.reflect.Field f = cc.getDeclaredField("mDeviceType"); f.setAccessible(true); devType = f.getInt(dev); break; } catch (Throwable e) {}
                java.lang.reflect.Method m = null;
                for (Class<?> cc = k; cc != null && m == null; cc = cc.getSuperclass())
                    try { m = cc.getDeclaredMethod("set", int.class, int.class, int.class); } catch (Throwable e) {}
                if (m == null) return "ERR no set(iii)";
                m.setAccessible(true);
                return "OK uid=" + android.os.Process.myUid() + " devType=" + devType
                        + " ret=" + m.invoke(dev, devType, Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            }
            if ("EV".equals(p[0])) return ev();
            if ("CONST".equals(p[0])) {                                  // CONST <class> <staticIntField>
                java.lang.reflect.Field f = Class.forName(p[1]).getField(p[2]);
                f.setAccessible(true);
                return "OK " + f.getInt(null) + " (0x" + Integer.toHexString(f.getInt(null)) + ")";
            }
            if ("DEVT".equals(p[0])) {                                   // DEVT <deviceClass> -> mDeviceType
                Class<?> k = Class.forName(p[1]);
                java.lang.reflect.Field f = null;
                for (Class<?> c = k; c != null && f == null; c = c.getSuperclass())
                    try { f = c.getDeclaredField("mDeviceType"); } catch (Throwable e) {}
                if (f == null) return "ERR no mDeviceType";
                f.setAccessible(true); return "OK devType=" + f.getInt(null);
            }
            return "ERR unknown";
        } catch (Throwable t) { return "ERR " + t; }
    }

    private static int transact(int tx, int dev, int fid, int value, boolean write) {
        Parcel d = Parcel.obtain(), rp = Parcel.obtain();
        try {
            d.writeInterfaceToken(iface);
            d.writeInt(dev); d.writeInt(fid);
            if (write) d.writeInt(value);
            svc.transact(tx, d, rp, 0);
            return rp.dataAvail() >= 4 ? rp.readInt() : -999;
        } catch (Throwable t) { log("transact fail tx=" + tx + " dev=" + dev + " fid=" + fid + ": " + t); return -998; }
        finally { rp.recycle(); d.recycle(); }
    }

    private static String ev() {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(ENERGY_DB, null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = db.rawQuery("SELECT soc, mileage, total_elec, ignition FROM last_state WHERE id=1", null);
            String r = c.moveToFirst()
                    ? "OK soc=" + c.getInt(0) + " mileage=" + c.getDouble(1) + " total_elec=" + c.getDouble(2) + " ign=" + c.getInt(3)
                    : "ERR no_row";
            c.close();
            return r;
        } catch (Throwable t) { return "ERR " + t; }
        finally { if (db != null) try { db.close(); } catch (Throwable e) {} }
    }

    /** Acquire a system Context from app_process (no Application). Needed by BYDAuto*Device.getInstance. */
    private static Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            try { Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null); } catch (Throwable e) {}
            Object th = at.getMethod("systemMain").invoke(null);
            Context c = (Context) at.getMethod("getSystemContext").invoke(th);
            log("ctx ok " + c);
            return c;
        } catch (Throwable t) { log("ctx fail: " + t); return null; }
    }

    private static void log(String m) {
        try { FileWriter w = new FileWriter("/data/local/tmp/autohelper.log", true);
              w.write(System.currentTimeMillis() + " " + m + "\n"); w.close(); } catch (Throwable e) {}
    }
}
