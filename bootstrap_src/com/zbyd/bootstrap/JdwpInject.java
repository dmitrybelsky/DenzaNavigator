package com.zbyd.bootstrap;

import java.nio.ByteBuffer;

/**
 * JDWP in-process dex injector (Java port of jdwp_inject.py) over an ADB `jdwp:<pid>` stream.
 * Loads HudPrivAgent.dex into a debuggable app (com.byd.cameraautostudy) and calls
 * HudPrivAgent.start() — re-establishing the privileged agent after a reboot without a PC.
 * The caller (BootstrapService) opens the jdwp stream and pokes the app (separate connection) so a
 * breakpoint on Handler.dispatchMessage hits, giving a thread suspended at a safe-point.
 */
public final class JdwpInject {

    private final AdbClient.AdbStream s;
    private int osz = 8, msz = 8, tsz = 8;
    private int id = 1;
    long tid = 0;

    public JdwpInject(AdbClient.AdbStream stream) { s = stream; }

    /** Full inject: handshake -> idsizes -> breakpoint(Handler.dispatchMessage) -> wait -> load dex -> start(). */
    public String inject(String dexPath, String optDir, String entryClass) throws Exception {
        s.write("JDWP-Handshake".getBytes("UTF-8"));
        byte[] hs = s.readN(14);
        if (!new String(hs, "UTF-8").equals("JDWP-Handshake")) throw new Exception("handshake fail");
        idsizes();
        int tag_hcid[] = classBySig("Landroid/os/Handler;");
        long hcid = ((long) tag_hcid[1] << 32) | (tag_hcid[2] & 0xffffffffL); // not used; methods() takes refId
        long handlerCid = lastRefId;
        long dmid = methodId(handlerCid, "dispatchMessage", "(Landroid/os/Message;)V");
        int rid = setBreakpoint(tag_hcid[0], handlerCid, dmid);
        resume();
        tid = waitBreakpoint();           // blocks until poke triggers dispatchMessage
        clearBreakpoint(rid);

        long sysLoader = systemClassLoader();
        long dcl = newDexClassLoader(dexPath, optDir, sysLoader);
        long klass = loadClass(dcl, entryClass);
        long[] tt = reflectedType(klass);
        long etid = tt[1];
        long start = methodId(etid, "start", null);
        invokeStatic(etid, start);
        resume();
        return "ok tid=" + tid;
    }

    // ---- JDWP packet plumbing ----------------------------------------------------------------
    private byte[] cmd(int cs, int c, byte[] data) throws Exception {
        int pid = id++;
        ByteBuffer h = ByteBuffer.allocate(11 + (data == null ? 0 : data.length));
        h.putInt(11 + (data == null ? 0 : data.length)); h.putInt(pid); h.put((byte) 0); h.put((byte) cs); h.put((byte) c);
        if (data != null) h.put(data);
        s.write(h.array());
        while (true) {
            byte[] head = s.readN(11);
            ByteBuffer hb = ByteBuffer.wrap(head);
            int len = hb.getInt(); int rpid = hb.getInt(); int flags = hb.get() & 0xff; int err = hb.getShort() & 0xffff;
            byte[] body = len > 11 ? s.readN(len - 11) : new byte[0];
            if ((flags & 0x80) != 0 && rpid == pid) { if (err != 0) throw new Exception("JDWP err " + err); return body; }
            // else: event composite — stash if breakpoint
            if ((flags & 0x80) == 0 && (head[9] & 0xff) == 64 && (head[10] & 0xff) == 100) lastEvent = body;
        }
    }
    private byte[] lastEvent; private long lastRefId;

    private void idsizes() throws Exception {
        byte[] d = cmd(1, 7, null); ByteBuffer b = ByteBuffer.wrap(d);
        b.getInt(); msz = b.getInt(); osz = b.getInt(); tsz = b.getInt();
    }
    private long rid(ByteBuffer b) { return osz == 8 ? b.getLong() : (b.getInt() & 0xffffffffL); }
    private long rmid(ByteBuffer b) { return msz == 8 ? b.getLong() : (b.getInt() & 0xffffffffL); }
    private byte[] oid(long v) { ByteBuffer b = ByteBuffer.allocate(osz); if (osz == 8) b.putLong(v); else b.putInt((int) v); return b.array(); }
    private byte[] tidb(long v) { ByteBuffer b = ByteBuffer.allocate(tsz); if (tsz == 8) b.putLong(v); else b.putInt((int) v); return b.array(); }
    private byte[] midb(long v) { ByteBuffer b = ByteBuffer.allocate(msz); if (msz == 8) b.putLong(v); else b.putInt((int) v); return b.array(); }

    private int[] classBySig(String sig) throws Exception {
        byte[] sb = sig.getBytes("UTF-8");
        ByteBuffer d = ByteBuffer.allocate(4 + sb.length); d.putInt(sb.length); d.put(sb);
        byte[] r = cmd(1, 2, d.array()); ByteBuffer b = ByteBuffer.wrap(r);
        int n = b.getInt(); if (n == 0) throw new Exception("class not loaded: " + sig);
        int tag = b.get() & 0xff; long cid = rid(b); lastRefId = cid;
        return new int[]{tag, 0, 0};
    }
    private long methodId(long refId, String name, String sigWant) throws Exception {
        byte[] r = cmd(2, 5, oid(refId)); ByteBuffer b = ByteBuffer.wrap(r);
        int n = b.getInt(); long found = 0;
        for (int i = 0; i < n; i++) {
            long mid = rmid(b);
            String nm = readStr(b), sg = readStr(b), gen = readStr(b); int mod = b.getInt();
            if (nm.equals(name) && (sigWant == null || sg.equals(sigWant) || (sigWant == null && sg.startsWith("()")))) found = mid;
            if (nm.equals(name) && sigWant == null && sg.startsWith("()")) { found = mid; }
        }
        if (found == 0) throw new Exception("method " + name + " not found");
        return found;
    }
    private String readStr(ByteBuffer b) { int n = b.getInt(); byte[] s2 = new byte[n]; b.get(s2); try { return new String(s2, "UTF-8"); } catch (Exception e) { return ""; } }

    private int setBreakpoint(int tag, long cid, long mid) throws Exception {
        ByteBuffer loc = ByteBuffer.allocate(1 + osz + msz + 8);
        loc.put((byte) tag); loc.put(oid(cid)); loc.put(midb(mid)); loc.putLong(0);
        ByteBuffer d = ByteBuffer.allocate(6 + loc.capacity());
        d.put((byte) 2).put((byte) 2).putInt(1).put((byte) 7).put(loc.array());
        byte[] r = cmd(15, 1, d.array()); return ByteBuffer.wrap(r).getInt();
    }
    private void clearBreakpoint(int reqId) throws Exception { ByteBuffer d = ByteBuffer.allocate(5); d.put((byte) 2).putInt(reqId); cmd(15, 2, d.array()); }
    private void resume() throws Exception { cmd(1, 9, null); }

    private long waitBreakpoint() throws Exception {
        // resume already sent; read events until a breakpoint composite arrives
        while (true) {
            byte[] head = s.readN(11); ByteBuffer hb = ByteBuffer.wrap(head);
            int len = hb.getInt(); hb.getInt(); int flags = hb.get() & 0xff; int a = hb.get() & 0xff, c = hb.get() & 0xff;
            byte[] body = len > 11 ? s.readN(len - 11) : new byte[0];
            if ((flags & 0x80) == 0 && a == 64 && c == 100) {
                ByteBuffer b = ByteBuffer.wrap(body); b.get(); int nev = b.getInt();
                int kind = b.get() & 0xff; b.getInt(); long t = rid(b);
                if (kind == 2) return t;
            }
        }
    }

    private long systemClassLoader() throws Exception {
        classBySig("Ljava/lang/ClassLoader;"); long cid = lastRefId;
        long mid = methodId(cid, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        return invokeStaticVal(cid, mid, new byte[0], 0);
    }
    private long createString(String str) throws Exception {
        byte[] sb = str.getBytes("UTF-8"); ByteBuffer d = ByteBuffer.allocate(4 + sb.length); d.putInt(sb.length); d.put(sb);
        byte[] r = cmd(1, 11, d.array()); return rid(ByteBuffer.wrap(r));
    }
    private long newDexClassLoader(String dex, String opt, long parent) throws Exception {
        classBySig("Ldalvik/system/DexClassLoader;"); long cid = lastRefId;
        long ctor = methodId(cid, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
        long sp = createString(dex), op = createString(opt);
        ByteBuffer args = ByteBuffer.allocate(4 + 4 * (1 + osz));
        args.putInt(4);
        args.put((byte) 76).put(oid(sp)); args.put((byte) 76).put(oid(op)); args.put((byte) 76).put(oid(0)); args.put((byte) 76).put(oid(parent));
        ByteBuffer d = ByteBuffer.allocate(osz + tsz + msz + args.capacity() + 4);
        d.put(oid(cid)).put(tidb(tid)).put(midb(ctor)).put(args.array()).putInt(1);
        byte[] r = cmd(3, 4, d.array()); ByteBuffer b = ByteBuffer.wrap(r); b.get(); return rid(b);
    }
    private long loadClass(long loader, String name) throws Exception {
        classBySig("Ljava/lang/ClassLoader;"); long cid = lastRefId;
        long lc = methodId(cid, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        long sn = createString(name);
        ByteBuffer args = ByteBuffer.allocate(4 + 1 + osz); args.putInt(1).put((byte) 76).put(oid(sn));
        ByteBuffer d = ByteBuffer.allocate(osz + tsz + osz + msz + args.capacity() + 4);
        d.put(oid(loader)).put(tidb(tid)).put(oid(cid)).put(midb(lc)).put(args.array()).putInt(1);
        byte[] r = cmd(9, 6, d.array()); ByteBuffer b = ByteBuffer.wrap(r); b.get(); return rid(b);
    }
    private long[] reflectedType(long classObj) throws Exception {
        byte[] r = cmd(17, 1, oid(classObj)); ByteBuffer b = ByteBuffer.wrap(r);
        int tag = b.get() & 0xff; long t = rid(b); return new long[]{tag, t};
    }
    private void invokeStatic(long typeId, long methodId) throws Exception { invokeStaticVal(typeId, methodId, new byte[0], 0); }
    private long invokeStaticVal(long typeId, long methodId, byte[] argsPayload, int nargs) throws Exception {
        ByteBuffer d = ByteBuffer.allocate(osz + tsz + msz + 4 + argsPayload.length + 4);
        d.put(oid(typeId)).put(tidb(tid)).put(midb(methodId)).putInt(nargs).put(argsPayload).putInt(1);
        byte[] r = cmd(3, 3, d.array()); ByteBuffer b = ByteBuffer.wrap(r); b.get(); return rid(b);
    }
}
