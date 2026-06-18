package com.zbyd.bootstrap;

import java.nio.ByteBuffer;

/**
 * On-device JDWP in-process dex injector (Java port of jdwp_inject.py) over an ADB jdwp:<pid> stream.
 * Loads HudPrivAgent into the debuggable platform_app com.byd.cameraautostudy and calls start(), so the
 * agent runs with the signature BYDAUTO_*_SET perms after a reboot without a PC.
 *
 * Mirrors the validated PC injector:
 *  - breakpoint on Handler.dispatchMessage; the caller pokes with a config change (cmd uimode) so the
 *    MAIN thread dispatches a message -> a thread suspended at a Java safe point.
 *  - ReferenceType.Methods (2,5) reply has NO generic field (the earlier port over-read it).
 *  - dex is loaded with InMemoryDexClassLoader from BYTES read via Files.readAllBytes — sidesteps the
 *    Android 11+ "writable dex" (W^X) block that refuses to execute dex from an app-writable file. The
 *    dex is read from /sdcard/zbyd (mlstrustedobject; cameraautostudy holds MANAGE_EXTERNAL_STORAGE).
 *  - parent = the app classloader (ActivityThread.currentApplication().getClassLoader()).
 */
public final class JdwpInject {

    private static final int TAG_OBJECT = 76; // 'L'
    private static final int TAG_ARRAY  = 91; // '['

    private final AdbClient.AdbStream s;
    private int osz = 8, msz = 8, tsz = 8;
    private int id = 1;
    long tid = 0;

    public JdwpInject(AdbClient.AdbStream stream) { s = stream; }

    /** Full inject: handshake -> idsizes -> breakpoint -> wait -> read dex bytes -> InMemoryDexClassLoader -> start(). */
    public String inject(String dexPath, String unusedOptDir, String entryClass) throws Exception {
        s.write("JDWP-Handshake".getBytes("UTF-8"));
        if (!new String(s.readN(14), "UTF-8").equals("JDWP-Handshake")) throw new Exception("handshake fail");
        idsizes();

        long handlerCid = classBySig("Landroid/os/Handler;");
        long dmid = methodId(handlerCid, "dispatchMessage", "(Landroid/os/Message;)V");
        int rid = setBreakpoint((byte) 1, handlerCid, dmid);   // tag CLASS=1
        resume();
        tid = waitBreakpoint();                                // blocks until the poke triggers dispatchMessage
        clearBreakpoint(rid);

        // parent = app classloader
        long atCid = classBySig("Landroid/app/ActivityThread;");
        long camMid = methodId(atCid, "currentApplication", "()Landroid/app/Application;");
        long app = invokeStatic(atCid, camMid, new int[0], new long[0]);
        long ctxCid = classBySig("Landroid/content/Context;");
        long gclMid = methodId(ctxCid, "getClassLoader", "()Ljava/lang/ClassLoader;");
        long loader = invokeInstance(app, ctxCid, gclMid, new int[0], new long[0]);

        // read dex bytes: Files.readAllBytes(Paths.get(dexPath))  (data read; W^X applies only to executing files)
        long sp = createString(dexPath);
        long strArrTid = classBySig("[Ljava/lang/String;");
        long emptyArr = arrayNew(strArrTid, 0);
        long pathsCid = classBySig("Ljava/nio/file/Paths;");
        long getMid = methodId(pathsCid, "get", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;");
        long path = invokeStatic(pathsCid, getMid, new int[]{TAG_OBJECT, TAG_ARRAY}, new long[]{sp, emptyArr});
        long filesCid = classBySig("Ljava/nio/file/Files;");
        long rabMid = methodId(filesCid, "readAllBytes", "(Ljava/nio/file/Path;)[B");
        long dexBytes = invokeStatic(filesCid, rabMid, new int[]{TAG_OBJECT}, new long[]{path});
        if (dexBytes == 0) throw new Exception("readAllBytes failed (dex unreadable: " + dexPath + ")");

        // ByteBuffer.wrap(bytes) -> new InMemoryDexClassLoader(bb, appLoader)
        long bbCid = classBySig("Ljava/nio/ByteBuffer;");
        long wrapMid = methodId(bbCid, "wrap", "([B)Ljava/nio/ByteBuffer;");
        long bb = invokeStatic(bbCid, wrapMid, new int[]{TAG_ARRAY}, new long[]{dexBytes});
        long imCid = classBySig("Ldalvik/system/InMemoryDexClassLoader;");
        long ctor = methodId(imCid, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        long dcl = newInstance(imCid, ctor, new int[]{TAG_OBJECT, TAG_OBJECT}, new long[]{bb, loader});

        // loadClass(entry) -> reflectedType -> static start()
        long clCid = classBySig("Ljava/lang/ClassLoader;");
        long lcMid = methodId(clCid, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        long klass = invokeInstance(dcl, clCid, lcMid, new int[]{TAG_OBJECT}, new long[]{createString(entryClass)});
        if (klass == 0) throw new Exception("loadClass returned null (dex not loaded)");
        long etid = reflectedType(klass);
        long start = methodId(etid, "start", null);
        invokeStatic(etid, start, new int[0], new long[0]);
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
            // else: async composite event (e.g. our breakpoint) — ignore while awaiting a reply
        }
    }

    private void idsizes() throws Exception {
        byte[] d = cmd(1, 7, null); ByteBuffer b = ByteBuffer.wrap(d);
        b.getInt(); msz = b.getInt(); osz = b.getInt(); tsz = b.getInt();
    }
    private long rid(ByteBuffer b) { return osz == 8 ? b.getLong() : (b.getInt() & 0xffffffffL); }
    private long rmid(ByteBuffer b) { return msz == 8 ? b.getLong() : (b.getInt() & 0xffffffffL); }
    private byte[] oid(long v) { ByteBuffer b = ByteBuffer.allocate(osz); if (osz == 8) b.putLong(v); else b.putInt((int) v); return b.array(); }
    private byte[] tidb(long v) { ByteBuffer b = ByteBuffer.allocate(tsz); if (tsz == 8) b.putLong(v); else b.putInt((int) v); return b.array(); }
    private byte[] midb(long v) { ByteBuffer b = ByteBuffer.allocate(msz); if (msz == 8) b.putLong(v); else b.putInt((int) v); return b.array(); }

    /** VirtualMachine.ClassesBySignature (1,2) -> first matching reference type id. */
    private long classBySig(String sig) throws Exception {
        byte[] sb = sig.getBytes("UTF-8");
        ByteBuffer d = ByteBuffer.allocate(4 + sb.length); d.putInt(sb.length); d.put(sb);
        byte[] r = cmd(1, 2, d.array()); ByteBuffer b = ByteBuffer.wrap(r);
        int n = b.getInt(); if (n == 0) throw new Exception("class not loaded: " + sig);
        b.get(); /* tag */ return rid(b);
    }

    /** ReferenceType.Methods (2,5): per method = methodId, name, signature, modBits (NO generic). */
    private long methodId(long refId, String name, String sigWant) throws Exception {
        byte[] r = cmd(2, 5, oid(refId)); ByteBuffer b = ByteBuffer.wrap(r);
        int n = b.getInt(); long found = 0;
        for (int i = 0; i < n; i++) {
            long mid = rmid(b);
            String nm = readStr(b), sg = readStr(b); b.getInt(); // modBits
            if (nm.equals(name) && (sigWant == null ? sg.startsWith("()") : sg.equals(sigWant))) found = mid;
        }
        if (found == 0) throw new Exception("method " + name + " not found on " + refId);
        return found;
    }
    private String readStr(ByteBuffer b) { int n = b.getInt(); byte[] s2 = new byte[n]; b.get(s2); try { return new String(s2, "UTF-8"); } catch (Exception e) { return ""; } }

    private int setBreakpoint(byte tag, long cid, long mid) throws Exception {
        ByteBuffer loc = ByteBuffer.allocate(1 + osz + msz + 8);
        loc.put(tag); loc.put(oid(cid)); loc.put(midb(mid)); loc.putLong(0);
        ByteBuffer d = ByteBuffer.allocate(6 + loc.capacity());
        d.put((byte) 2).put((byte) 2).putInt(1).put((byte) 7).put(loc.array());   // BREAKPOINT, SUSPEND_ALL, 1 mod, LocationOnly
        return ByteBuffer.wrap(cmd(15, 1, d.array())).getInt();
    }
    private void clearBreakpoint(int reqId) throws Exception { ByteBuffer d = ByteBuffer.allocate(5); d.put((byte) 2).putInt(reqId); cmd(15, 2, d.array()); }
    private void resume() throws Exception { cmd(1, 9, null); }

    /** Read events (after resume) until a BREAKPOINT composite arrives; return its thread id. */
    private long waitBreakpoint() throws Exception {
        while (true) {
            byte[] head = s.readN(11); ByteBuffer hb = ByteBuffer.wrap(head);
            int len = hb.getInt(); hb.getInt(); int flags = hb.get() & 0xff; int a = hb.get() & 0xff, c = hb.get() & 0xff;
            byte[] body = len > 11 ? s.readN(len - 11) : new byte[0];
            if ((flags & 0x80) == 0 && a == 64 && c == 100) {            // Composite event
                ByteBuffer b = ByteBuffer.wrap(body); b.get(); b.getInt(); // suspendPolicy, events count
                int kind = b.get() & 0xff; b.getInt();                    // eventKind, requestID
                long t = rid(b);
                if (kind == 2) return t;                                  // BREAKPOINT
            }
        }
    }

    private long createString(String str) throws Exception {
        byte[] sb = str.getBytes("UTF-8"); ByteBuffer d = ByteBuffer.allocate(4 + sb.length); d.putInt(sb.length); d.put(sb);
        return rid(ByteBuffer.wrap(cmd(1, 11, d.array())));
    }
    /** ArrayType.NewInstance (4,1) -> new array object id. */
    private long arrayNew(long arrTypeId, int length) throws Exception {
        ByteBuffer d = ByteBuffer.allocate(osz + 4); d.put(oid(arrTypeId)).putInt(length);
        byte[] r = cmd(4, 1, d.array()); ByteBuffer b = ByteBuffer.wrap(r); b.get(); return rid(b);
    }
    private long reflectedType(long classObj) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(cmd(17, 1, oid(classObj))); b.get(); return rid(b);
    }

    /** Encode an argument vector: count + per arg (tag byte + osz-wide value). */
    private byte[] packArgs(int[] tags, long[] vals) {
        ByteBuffer a = ByteBuffer.allocate(4 + tags.length * (1 + osz));
        a.putInt(tags.length);
        for (int i = 0; i < tags.length; i++) { a.put((byte) tags[i]); a.put(oid(vals[i])); }
        return a.array();
    }

    /** ClassType.InvokeMethod (3,3) static. */
    private long invokeStatic(long typeId, long methodId, int[] tags, long[] vals) throws Exception {
        byte[] args = packArgs(tags, vals);
        ByteBuffer d = ByteBuffer.allocate(osz + tsz + msz + args.length + 4);
        d.put(oid(typeId)).put(tidb(tid)).put(midb(methodId)).put(args).putInt(1);   // INVOKE_SINGLE_THREADED
        ByteBuffer b = ByteBuffer.wrap(cmd(3, 3, d.array())); b.get(); return rid(b);
    }
    /** ObjectReference.InvokeMethod (9,6) virtual. */
    private long invokeInstance(long obj, long classId, long methodId, int[] tags, long[] vals) throws Exception {
        byte[] args = packArgs(tags, vals);
        ByteBuffer d = ByteBuffer.allocate(osz + tsz + osz + msz + args.length + 4);
        d.put(oid(obj)).put(tidb(tid)).put(oid(classId)).put(midb(methodId)).put(args).putInt(1);
        ByteBuffer b = ByteBuffer.wrap(cmd(9, 6, d.array())); b.get(); return rid(b);
    }
    /** ClassType.NewInstance (3,4). */
    private long newInstance(long classId, long ctorId, int[] tags, long[] vals) throws Exception {
        byte[] args = packArgs(tags, vals);
        ByteBuffer d = ByteBuffer.allocate(osz + tsz + msz + args.length + 4);
        d.put(oid(classId)).put(tidb(tid)).put(midb(ctorId)).put(args).putInt(1);
        ByteBuffer b = ByteBuffer.wrap(cmd(3, 4, d.array())); b.get(); return rid(b);   // tagged object value
    }
}
