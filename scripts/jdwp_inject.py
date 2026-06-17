#!/usr/bin/env python3
# JDWP in-process dex injector (non-root).
# Loads an agent dex into a DEBUGGABLE target app and invokes <entryClass>.<entryMethod>().
# Used to run HudPrivAgent inside com.byd.cameraautostudy (holds BYDAUTO_INSTRUMENT_SET +
# BYDAUTO_SETTING_SET) so privileged BYD HAL calls pass the per-UID permission check.
#
# Mechanism: handshake -> IDSizes -> set BREAKPOINT on android.os.Handler.dispatchMessage to obtain
# a thread suspended at a Java safe-point -> CreateString + ClassLoader.getSystemClassLoader() ->
# new DexClassLoader(dexPath,optDir,null,sysLoader) -> loadClass(entry) -> ReflectedType ->
# ClassType.InvokeMethod start(). The app is poked (am start) to guarantee dispatchMessage traffic.
#
# Usage: python3 jdwp_inject.py <serial> <package> <devDexPath> [entryClass] [entryMethod]
import socket, struct, subprocess, sys, time

SERIAL   = sys.argv[1]
PKG      = sys.argv[2]
DEX      = sys.argv[3]                                   # path ON DEVICE (world-readable, e.g. /data/local/tmp/HudPrivAgent.dex)
ENTRYCLS = sys.argv[4] if len(sys.argv) > 4 else "com.zbyd.hudpriv.HudPrivAgent"
ENTRYMTD = sys.argv[5] if len(sys.argv) > 5 else "start"
OPTDIR   = "/data/data/%s/code_cache/zbyd-odex" % PKG   # app-WRITABLE (the loaded app owns it); /data/local/tmp is shell-owned -> odex fails
PORT     = 7720
POKE     = sys.argv[6] if len(sys.argv) > 6 else (PKG + "/.CameraAutoStudyTest" if PKG == "com.byd.cameraautostudy" else None)

def adb(*a, **k):
    return subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, **k)

def pid_of():
    r = adb("shell", "pidof", PKG)
    return r.stdout.strip().split()[0] if r.stdout.strip() else None

# ---- JDWP packet plumbing ----------------------------------------------------
class JDWP:
    def __init__(self, sock):
        self.s = sock; self.id = 1
        self.s.sendall(b"JDWP-Handshake")
        if self.s.recv(14) != b"JDWP-Handshake": raise RuntimeError("handshake failed")
        self.osz = self.msz = self.fsz = self.tsz = self.ssz = 8   # filled by idsizes()
        self.tid = 0

    def cmd(self, cs, c, data=b""):
        pid = self.id; self.id += 1
        pkt = struct.pack(">IIBBB", 11 + len(data), pid, 0, cs, c) + data
        self.s.sendall(pkt)
        return self.reply(pid)

    def reply(self, want):
        while True:
            head = self._recvn(11)
            length, pid, flags, a, b = struct.unpack(">IIBBB", head)
            body = self._recvn(length - 11)
            if flags & 0x80 and pid == want:
                err = (a << 8) | b
                if err: raise RuntimeError("JDWP error %d (cmd id %d)" % (err, pid))
                return body
            # else: event/composite or other reply — ignore here (we poll events separately)

    def _recvn(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk: raise RuntimeError("socket closed")
            buf += chunk
        return buf

    def idsizes(self):
        d = self.cmd(1, 7)
        self.fsz, self.msz, self.osz, self.tsz, self.ssz = struct.unpack(">IIIII", d[:20])

    def oid(self, v): return v.to_bytes(self.osz, "big")
    def rd_oid(self, d, o): return int.from_bytes(d[o:o+self.osz], "big"), o + self.osz
    def rd_mid(self, d, o): return int.from_bytes(d[o:o+self.msz], "big"), o + self.msz

    def create_string(self, s):
        b = s.encode("utf-8")
        d = self.cmd(1, 11, struct.pack(">I", len(b)) + b)
        return int.from_bytes(d[:self.osz], "big")

    def classes_by_sig(self, sig):
        b = sig.encode("utf-8")
        d = self.cmd(1, 2, struct.pack(">I", len(b)) + b)
        n = struct.unpack(">I", d[:4])[0]
        if n == 0: raise RuntimeError("class not loaded: " + sig)
        # entry: refTypeTag(1) + referenceTypeID(osz) + status(4)
        tag = d[4]; cid = int.from_bytes(d[5:5+self.osz], "big")
        return tag, cid

    def methods(self, cid):
        d = self.cmd(2, 5, self.oid(cid)); o = 0
        n = struct.unpack(">I", d[o:o+4])[0]; o += 4
        out = {}
        for _ in range(n):
            mid, o = self.rd_mid(d, o)
            nl = struct.unpack(">I", d[o:o+4])[0]; o += 4; name = d[o:o+nl].decode("utf-8", "replace"); o += nl
            sl = struct.unpack(">I", d[o:o+4])[0]; o += 4; sig = d[o:o+sl].decode("utf-8", "replace"); o += sl
            mod = struct.unpack(">I", d[o:o+4])[0]; o += 4   # ReferenceType.Methods (2,5): no generic field
            out.setdefault(name, []).append((mid, sig))
        return out

    def set_breakpoint(self, tag, cid, mid):
        # EventRequest.Set: eventKind=BREAKPOINT(2), suspend=ALL(2), 1 modifier LocationOnly(7)
        loc = struct.pack(">B", tag) + self.oid(cid) + mid.to_bytes(self.msz, "big") + struct.pack(">Q", 0)
        data = struct.pack(">BBI", 2, 2, 1) + struct.pack(">B", 7) + loc
        d = self.cmd(15, 1, data)
        return struct.unpack(">I", d[:4])[0]

    def clear_breakpoint(self, rid):
        self.cmd(15, 2, struct.pack(">BI", 2, rid))

    def resume(self):  self.cmd(1, 9)
    def suspend(self): self.cmd(1, 8)

    def wait_breakpoint(self, rid, timeout=30):
        self.s.settimeout(timeout)
        while True:
            head = self._recvn(11)
            length, pid, flags, a, b = struct.unpack(">IIBBB", head)
            body = self._recvn(length - 11)
            if not (flags & 0x80) and a == 64 and b == 100:    # Composite event
                # suspendPolicy(1) events(4) then events; first event: eventKind(1) requestID(4) thread(osz) location
                o = 1; nev = struct.unpack(">I", body[o:o+4])[0]; o += 4
                kind = body[o]; o += 1
                req = struct.unpack(">I", body[o:o+4])[0]; o += 4
                tid = int.from_bytes(body[o:o+self.osz], "big"); o += self.osz
                sys.stderr.write("[evt] composite kind=%d req=%d\n" % (kind, req))
                if kind == 2:                                  # BREAKPOINT
                    return tid

    def system_classloader(self):
        tag, cid = self.classes_by_sig("Ljava/lang/ClassLoader;")
        m = self.methods(cid)
        mid = next(mid for (mid, sig) in m["getSystemClassLoader"] if sig == "()Ljava/lang/ClassLoader;")
        # ClassType.InvokeMethod static, 0 args
        d = self.invoke_static(cid, self.tid, mid, [])
        return d  # objectID of the loader (a tagged-value 'L')

    def invoke_static(self, cid, tid, mid, args):
        data = self.oid(cid) + tid.to_bytes(self.tsz, "big") + mid.to_bytes(self.msz, "big")
        data += struct.pack(">I", len(args))
        for t, v in args: data += struct.pack(">B", t) + self.oid(v)
        data += struct.pack(">I", 1)                           # INVOKE_SINGLE_THREADED
        d = self.cmd(3, 3, data)
        # returnValue: tagged value, then exception objectID
        tagv = d[0]; val = int.from_bytes(d[1:1+self.osz], "big")
        exc = int.from_bytes(d[1+self.osz+1:1+self.osz+1+self.osz], "big")
        if exc: sys.stderr.write("[exc] invoke_static threw %s\n" % self.exc_sig(exc))
        return val

    def invoke_instance(self, obj, cid, tid, mid, args):
        data = self.oid(obj) + tid.to_bytes(self.tsz, "big") + self.oid(cid) + mid.to_bytes(self.msz, "big")
        data += struct.pack(">I", len(args))
        for t, v in args: data += struct.pack(">B", t) + self.oid(v)
        data += struct.pack(">I", 1)
        d = self.cmd(9, 6, data)
        val = int.from_bytes(d[1:1+self.osz], "big")
        exc = int.from_bytes(d[1+self.osz+1:1+self.osz+1+self.osz], "big")
        if exc: sys.stderr.write("[exc] invoke_instance threw %s\n" % self.exc_sig(exc))
        return val

    def exc_sig(self, objid):
        try:
            r = self.cmd(9, 1, self.oid(objid))               # ObjectReference.ReferenceType -> tag + refTypeID
            rt = int.from_bytes(r[1:1+self.osz], "big")
            s = self.cmd(2, 1, self.oid(rt))                  # ReferenceType.Signature -> string
            n = struct.unpack(">I", s[:4])[0]
            return s[4:4+n].decode("utf-8", "replace")
        except Exception as e:
            return "obj=%d (sig fetch failed: %s)" % (objid, e)

    def new_instance(self, cid, tid, mid, args):
        data = self.oid(cid) + tid.to_bytes(self.tsz, "big") + mid.to_bytes(self.msz, "big")
        data += struct.pack(">I", len(args))
        for t, v in args: data += struct.pack(">B", t) + self.oid(v)
        data += struct.pack(">I", 1)
        d = self.cmd(3, 4, data)
        # returns tagged object value + exception
        val = int.from_bytes(d[1:1+self.osz], "big")
        return val

    def array_new(self, arr_typeid, length):
        d = self.cmd(4, 1, self.oid(arr_typeid) + struct.pack(">I", length))   # ArrayType.NewInstance
        return int.from_bytes(d[1:1+self.osz], "big")

    def reflected_type(self, classobj):
        d = self.cmd(17, 1, self.oid(classobj))
        tag = d[0]; tid = int.from_bytes(d[1:1+self.osz], "big")
        return tag, tid

TAG_OBJECT = 76  # 'L'
TAG_ARRAY  = 91  # '['

def main():
    # 1. ensure app running, get pid (poke to generate looper traffic)
    if POKE: adb("shell", "am", "start", "-n", POKE)                   # poke persistent app to run looper
    pid = pid_of()
    if not pid:
        adb("shell", "monkey", "-p", PKG, "1")
        time.sleep(1.0); pid = pid_of()
    if not pid:
        print("[x] %s not running and could not be started" % PKG); sys.exit(1)
    print("[*] %s pid=%s" % (PKG, pid))
    adb("shell", "run-as", PKG, "mkdir", "-p", OPTDIR)   # app-owned odex dir
    adb("forward", "tcp:%d" % PORT, "jdwp:%s" % pid)
    s = socket.create_connection(("127.0.0.1", PORT), timeout=15)
    j = JDWP(s); j.idsizes()
    print("[*] connected, idsizes osz=%d msz=%d" % (j.osz, j.msz))

    # 2. breakpoint on Handler.dispatchMessage to get a suspended thread at a safe point
    tag, hcid = j.classes_by_sig("Landroid/os/Handler;")
    hm = j.methods(hcid)
    dmid = next(mid for (mid, sig) in hm["dispatchMessage"] if sig == "(Landroid/os/Message;)V")
    rid = j.set_breakpoint(tag, hcid, dmid)
    j.resume()
    print("[*] breakpoint set, poking app for looper traffic ...")
    # poke so the MAIN thread dispatches a message. Config change (uimode toggle) -> onConfigurationChanged
    # on every app's main thread via Handler -> reliable Handler.dispatchMessage. Also re-deliver intent w/ a
    # varying extra so onNewIntent fires even on singleTop.
    pokes = [
        ("shell", "cmd", "uimode", "night", "yes"),
        ("shell", "cmd", "uimode", "night", "no"),
    ]
    for i in range(10):
        if POKE: adb("shell", "am", "start", "-n", POKE, "--ez", "zbyd", "true", "-a", "android.intent.action.VIEW", "-d", "zbyd://%d" % i)
        adb(*pokes[i % len(pokes)])
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        try:
            j.tid = j.wait_breakpoint(rid, timeout=6)
            break
        except socket.timeout:
            print("[.] poke %d: no breakpoint yet" % i); continue
    else:
        print("[x] breakpoint never hit (app idle?). Try interacting with it on screen."); sys.exit(2)
    print("[*] thread suspended tid=%d" % j.tid)
    j.clear_breakpoint(rid)

    # 3. parent = APP classloader (ActivityThread.currentApplication().getClassLoader())
    _, atcid = j.classes_by_sig("Landroid/app/ActivityThread;")
    cam = next(mid for (mid, sig) in j.methods(atcid)["currentApplication"] if sig == "()Landroid/app/Application;")
    app = j.invoke_static(atcid, j.tid, cam, [])
    _, ctxcid = j.classes_by_sig("Landroid/content/Context;")
    gcl = next(mid for (mid, sig) in j.methods(ctxcid)["getClassLoader"] if sig == "()Ljava/lang/ClassLoader;")
    loader = j.invoke_instance(app, ctxcid, j.tid, gcl, [])
    print("[*] appClassLoader=%d (app=%d)" % (loader, app))

    # 4. Read the RAW dex bytes as DATA (allowed) and load from MEMORY -> bypasses the API29+ W^X block
    #    that refuses to *execute* dex from an app-writable file ("ClassLoader referenced unknown path").
    sp = j.create_string(DEX)                                          # DEX must be a raw .dex (not .jar) for InMemoryDexClassLoader
    _, strArrTid = j.classes_by_sig("[Ljava/lang/String;")
    emptyArr = j.array_new(strArrTid, 0)
    _, pathscid = j.classes_by_sig("Ljava/nio/file/Paths;")
    getp = next(mid for (mid, sig) in j.methods(pathscid)["get"] if sig == "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;")
    path = j.invoke_static(pathscid, j.tid, getp, [(TAG_OBJECT, sp), (TAG_ARRAY, emptyArr)])
    _, filescid = j.classes_by_sig("Ljava/nio/file/Files;")
    rab = next(mid for (mid, sig) in j.methods(filescid)["readAllBytes"] if sig == "(Ljava/nio/file/Path;)[B")
    dexbytes = j.invoke_static(filescid, j.tid, rab, [(TAG_OBJECT, path)])
    print("[*] read dex bytes -> byte[]=%d" % dexbytes)
    _, bbcid = j.classes_by_sig("Ljava/nio/ByteBuffer;")
    wrap = next(mid for (mid, sig) in j.methods(bbcid)["wrap"] if sig == "([B)Ljava/nio/ByteBuffer;")
    bb = j.invoke_static(bbcid, j.tid, wrap, [(TAG_ARRAY, dexbytes)])
    _, dclcid = j.classes_by_sig("Ldalvik/system/InMemoryDexClassLoader;")
    ctor = next(mid for (mid, sig) in j.methods(dclcid)["<init>"]
                if sig == "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V")
    dcl = j.new_instance(dclcid, j.tid, ctor, [(TAG_OBJECT, bb), (TAG_OBJECT, loader)])
    print("[*] InMemoryDexClassLoader=%d" % dcl)

    # 5. loadClass(entry) -> Class object
    clsname = j.create_string(ENTRYCLS)
    _, clcid = j.classes_by_sig("Ljava/lang/ClassLoader;")
    clm = j.methods(clcid)
    lc = next(mid for (mid, sig) in clm["loadClass"] if sig == "(Ljava/lang/String;)Ljava/lang/Class;")
    klass = j.invoke_instance(dcl, clcid, j.tid, lc, [(TAG_OBJECT, clsname)])
    print("[*] loaded %s -> classObj=%d" % (ENTRYCLS, klass))
    if not klass:
        j.resume(); adb("forward", "--remove", "tcp:%d" % PORT)   # don't pass null class to JDWP (CheckJNI aborts the app)
        print("[x] loadClass returned null -> dex not loaded. Aborting cleanly (app preserved)."); sys.exit(3)

    # 6. ReflectedType(classObj) -> typeID; find entry method; ClassType.InvokeMethod
    _, etid = j.reflected_type(klass)
    em = j.methods(etid)
    start = next(mid for (mid, sig) in em[ENTRYMTD] if sig.startswith("()"))
    ret = j.invoke_static(etid, j.tid, start, [])
    print("[*] %s.%s() invoked (ret obj=%d)" % (ENTRYCLS, ENTRYMTD, ret))

    j.resume()
    adb("forward", "--remove", "tcp:%d" % PORT)
    print("[ok] agent injected. socket @zbyd_hud_priv should now be listening.")

if __name__ == "__main__":
    main()
