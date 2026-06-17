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
OPTDIR   = "/data/local/tmp/zbyd-odex"
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
            nl = struct.unpack(">I", d[o:o+4])[0]; o += 4; name = d[o:o+nl].decode(); o += nl
            sl = struct.unpack(">I", d[o:o+4])[0]; o += 4; sig = d[o:o+sl].decode(); o += sl
            gl = struct.unpack(">I", d[o:o+4])[0]; o += 4; o += gl
            mod = struct.unpack(">I", d[o:o+4])[0]; o += 4
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
        return val

    def invoke_instance(self, obj, cid, tid, mid, args):
        data = self.oid(obj) + tid.to_bytes(self.tsz, "big") + self.oid(cid) + mid.to_bytes(self.msz, "big")
        data += struct.pack(">I", len(args))
        for t, v in args: data += struct.pack(">B", t) + self.oid(v)
        data += struct.pack(">I", 1)
        d = self.cmd(9, 6, data)
        val = int.from_bytes(d[1:1+self.osz], "big")
        return val

    def new_instance(self, cid, tid, mid, args):
        data = self.oid(cid) + tid.to_bytes(self.tsz, "big") + mid.to_bytes(self.msz, "big")
        data += struct.pack(">I", len(args))
        for t, v in args: data += struct.pack(">B", t) + self.oid(v)
        data += struct.pack(">I", 1)
        d = self.cmd(3, 4, data)
        # returns tagged object value + exception
        val = int.from_bytes(d[1:1+self.osz], "big")
        return val

    def reflected_type(self, classobj):
        d = self.cmd(17, 1, self.oid(classobj))
        tag = d[0]; tid = int.from_bytes(d[1:1+self.osz], "big")
        return tag, tid

TAG_OBJECT = 76  # 'L'

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
    adb("shell", "mkdir", "-p", OPTDIR)
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
    # poke repeatedly so the main thread dispatches messages
    for _ in range(8):
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        if POKE: adb("shell", "am", "start", "-n", POKE)
        try:
            j.tid = j.wait_breakpoint(rid, timeout=4)
            break
        except socket.timeout:
            continue
    else:
        print("[x] breakpoint never hit (app idle?). Try interacting with it on screen."); sys.exit(2)
    print("[*] thread suspended tid=%d" % j.tid)
    j.clear_breakpoint(rid)

    # 3. build args: dexPath, optDir, parent loader
    sp = j.create_string(DEX); op = j.create_string(OPTDIR)
    loader = j.system_classloader()
    print("[*] sysClassLoader=%d" % loader)

    # 4. new DexClassLoader(String,String,String,ClassLoader) -- pass null 3rd (libPath)
    _, dclcid = j.classes_by_sig("Ldalvik/system/DexClassLoader;")
    dclm = j.methods(dclcid)
    ctor = next(mid for (mid, sig) in dclm["<init>"]
                if sig == "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V")
    dcl = j.new_instance(dclcid, j.tid, ctor,
                         [(TAG_OBJECT, sp), (TAG_OBJECT, op), (TAG_OBJECT, 0), (TAG_OBJECT, loader)])
    print("[*] DexClassLoader=%d" % dcl)

    # 5. loadClass(entry) -> Class object
    clsname = j.create_string(ENTRYCLS)
    _, clcid = j.classes_by_sig("Ljava/lang/ClassLoader;")
    clm = j.methods(clcid)
    lc = next(mid for (mid, sig) in clm["loadClass"] if sig == "(Ljava/lang/String;)Ljava/lang/Class;")
    klass = j.invoke_instance(dcl, clcid, j.tid, lc, [(TAG_OBJECT, clsname)])
    print("[*] loaded %s -> classObj=%d" % (ENTRYCLS, klass))

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
