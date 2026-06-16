package com.zbyd.patcher;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * On-device dexlib2 patcher: re-applies the zbyd HUD mod to ANY freshly-updated Yandex Navi, non-root.
 * Reads the installed base.apk (world-readable), patches dex by SIGNATURE (so it survives obfuscation
 * moves across versions), edits the manifest (AxmlPatch), appends the bundled HudEvents.dex, re-signs
 * (apksig). Every step is independent + logged so a moved target is reported, not fatal.
 */
public final class PatchEngine {

    public interface Log { void line(String s); }
    private final Log log;
    public PatchEngine(Log l) { this.log = l; }
    private void ok(String s)  { log.line("[+] " + s); }
    private void skip(String s){ log.line("[!] " + s); }
    private void info(String s){ log.line("[=] " + s); }

    private static final String GUIDANCE_RET = "Lcom/yandex/navikit/guidance/Guidance;";
    private static final Opcodes OPS = Opcodes.forApi(26);

    /** Patch all dex entries of inApk -> outDir, returns the list of produced dex byte[] (classesN order). */
    public List<byte[]> patchDexes(File inApk) throws Exception {
        List<byte[]> out = new ArrayList<>();
        ZipFile zf = new ZipFile(inApk);
        try {
            // collect classes*.dex in order
            List<String> names = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) { String n = e.nextElement().getName();
                if (n.matches("classes\\d*\\.dex")) names.add(n); }
            Collections.sort(names, (a, b) -> num(a) - num(b));
            boolean paywall = false, speed = false, ocr = false; int guidance = 0;
            for (String n : names) {
                byte[] raw = readAll(zf.getInputStream(zf.getEntry(n)));
                DexBackedDexFile dex = new DexBackedDexFile(OPS, raw);
                List<ClassDef> classes = new ArrayList<>();
                boolean changed = false;
                for (ClassDef cd : dex.getClasses()) {
                    ClassDef nc = patchClass(cd);
                    if (nc != cd) changed = true;
                    classes.add(nc);
                    if (nc != cd) {
                        // crude per-patch accounting via markers set in patchClass
                        if (sLastPaywall) { paywall = true; sLastPaywall = false; }
                        if (sLastSpeed)   { speed = true;   sLastSpeed = false; }
                        if (sLastOcr)     { ocr = true;     sLastOcr = false; }
                        guidance += sLastGuidance; sLastGuidance = 0;
                    }
                }
                if (changed) {
                    DexPool pool = new DexPool(OPS);
                    for (ClassDef cd : classes) pool.internClass(cd);
                    MemoryDataStore store = new MemoryDataStore();
                    pool.writeTo(store);
                    out.add(store.getData());
                } else {
                    out.add(raw);
                }
            }
            if (paywall) ok("paywall k0.c -> true"); else skip("paywall: not found (version diff?)");
            if (guidance > 0) ok("guidance hook injected (" + guidance + " sites)"); else skip("guidance hook: no getGuidance call site found");
            if (speed) ok("speed-limit hook injected"); else skip("speed-limit hook: SpeedLimitView.setSpeedLimit not found");
            if (ocr) ok("OCR activity hook injected (MapActivity.onStart)"); else skip("OCR activity hook: MapActivity.onStart not found");
        } finally { zf.close(); }
        return out;
    }

    private boolean sLastPaywall, sLastSpeed, sLastOcr; private int sLastGuidance;

    /** prepend invoke-static {this}, HudTlOcr.setActivity(Activity) at the head of MapActivity.onStart. */
    private Method injectActivity(Method m) {
        try {
            MutableMethodImplementation impl = new MutableMethodImplementation(m.getImplementation());
            int thisReg = impl.getRegisterCount() - 1;   // onStart()V instance method: only param = this
            if (thisReg < 0 || thisReg > 15) return null;
            ImmutableMethodReference ref = new ImmutableMethodReference(
                "Lcom/zbyd/hudhook/HudTlOcr;", "setActivity",
                Collections.singletonList("Landroid/app/Activity;"), "V");
            impl.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, thisReg, 0, 0, 0, 0, ref));
            return rebuild(m, impl);
        } catch (Throwable t) { return null; }
    }

    private ClassDef patchClass(ClassDef cd) {
        String type = cd.getType();
        boolean isPaywall = type.endsWith("/integrations/projected/k0;") || type.endsWith("/projected/k0;");
        boolean isSpeed = type.endsWith("/ui/guidance/speed/SpeedLimitView;");
        boolean isMapAct = type.endsWith("/app/MapActivity;");
        List<Method> methods = null;
        for (Method m : cd.getMethods()) {
            MethodImplementation impl = m.getImplementation();
            if (impl == null) continue;
            Method nm = null;
            if (isPaywall && m.getName().equals("c") && m.getReturnType().equals("Z") && m.getParameters().isEmpty()) {
                nm = forceReturnTrue(m); if (nm != null) sLastPaywall = true;
            } else if (isSpeed && m.getName().equals("setSpeedLimit")) {
                nm = injectHead(m, "Lru/yandex/yandexnavi/hud/HudHook;", "sl", true);
                if (nm != null) sLastSpeed = true;   // (HudHook must exist; if absent this is inert — fine)
            } else if (isMapAct && m.getName().equals("onStart") && m.getReturnType().equals("V") && m.getParameters().isEmpty()) {
                nm = injectActivity(m); if (nm != null) sLastOcr = true;   // HudTlOcr.setActivity(this)
            } else if (hasGuidanceCapture(impl)) {
                nm = injectGuidanceHook(m); if (nm != null) sLastGuidance++;
            }
            if (nm != null) { if (methods == null) methods = new ArrayList<>(toList(cd.getMethods())); replace(methods, m, nm); }
        }
        if (methods == null) return cd;
        return new ImmutableClassDef(cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
            cd.getInterfaces(), cd.getSourceFile(), cd.getAnnotations(), cd.getFields(), methods);
    }

    /** method body -> {const/4 v0,1; return v0}. */
    private Method forceReturnTrue(Method m) {
        MutableMethodImplementation impl = new MutableMethodImplementation(1);
        impl.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 1));
        impl.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), impl);
    }

    /** true if impl invokes *.getGuidance()->Guidance immediately followed by move-result-object. */
    private boolean hasGuidanceCapture(MethodImplementation impl) {
        List<? extends Instruction> ins = toList(impl.getInstructions());
        for (int i = 0; i + 1 < ins.size(); i++) {
            Instruction a = ins.get(i);
            if (a instanceof ReferenceInstruction) {
                Object r = ((ReferenceInstruction) a).getReference();
                if (r instanceof MethodReference) { MethodReference mr = (MethodReference) r;
                    if (mr.getName().equals("getGuidance") && GUIDANCE_RET.equals(mr.getReturnType())
                        && ins.get(i + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT) return true;
                }
            }
        }
        return false;
    }

    /** insert invoke-static {vDest}, HudEvents.setGuidance(Object) right after the move-result-object. */
    private Method injectGuidanceHook(Method m) {
        MutableMethodImplementation impl = new MutableMethodImplementation(m.getImplementation());
        List<com.android.tools.smali.dexlib2.builder.BuilderInstruction> ins = impl.getInstructions();
        for (int i = 0; i + 1 < ins.size(); i++) {
            Instruction a = ins.get(i);
            if (a instanceof ReferenceInstruction) {
                Object r = ((ReferenceInstruction) a).getReference();
                if (r instanceof MethodReference && ((MethodReference) r).getName().equals("getGuidance")
                    && GUIDANCE_RET.equals(((MethodReference) r).getReturnType())
                    && ins.get(i + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT) {
                    int reg = ((com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) ins.get(i + 1)).getRegisterA();
                    if (reg > 15) return null;   // 35c needs 4-bit regs; skip rare wide-reg sites
                    ImmutableMethodReference ref = new ImmutableMethodReference(
                        "Lcom/zbyd/hudhook/HudEvents;", "setGuidance",
                        Collections.singletonList("Ljava/lang/Object;"), "V");
                    impl.addInstruction(i + 2, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, reg, 0, 0, 0, 0, ref));
                    return rebuild(m, impl);
                }
            }
        }
        return null;
    }

    /** prepend invoke-static {p0[,p1]}, target.name(...) at method head (best-effort, used for speed-limit). */
    private Method injectHead(Method m, String cls, String name, boolean twoArg) {
        try {
            MutableMethodImplementation impl = new MutableMethodImplementation(m.getImplementation());
            int regCount = impl.getRegisterCount();
            int pCount = m.getParameters().size() + (com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(m.getAccessFlags()) ? 0 : 1);
            int p0 = regCount - pCount;            // first param register
            if (p0 < 0 || p0 > 15) return null;
            ImmutableMethodReference ref = new ImmutableMethodReference(cls, name,
                Arrays.asList("Landroid/view/View;", "Ljava/lang/String;"), "V");
            impl.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, p0, p0 + 1, 0, 0, 0, ref));
            return rebuild(m, impl);
        } catch (Throwable t) { return null; }
    }

    private Method rebuild(Method m, MutableMethodImplementation impl) {
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), impl);
    }

    // ---- helpers ----
    private static int num(String n) { String d = n.replaceAll("\\D", ""); return d.isEmpty() ? 1 : Integer.parseInt(d); }
    private static <T> List<T> toList(Iterable<T> it) { List<T> l = new ArrayList<>(); for (T t : it) l.add(t); return l; }
    private static void replace(List<Method> l, Method oldM, Method newM) { for (int i = 0; i < l.size(); i++) if (l.get(i) == oldM) { l.set(i, newM); return; } }
    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n); in.close(); return o.toByteArray();
    }

    /** Repackage: original apk + replaced dexes + appended HudEvents.dex (next classesN) -> outApk (unsigned). */
    public void repackage(File inApk, List<byte[]> dexes, byte[] hudDex, byte[] patchedManifest, File outApk) throws Exception {
        ZipFile zf = new ZipFile(inApk);
        ZipOutputStream zo = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(outApk), 1 << 20));
        zo.setLevel(java.util.zip.Deflater.NO_COMPRESSION);   // don't re-deflate 336MB (was the bottleneck)
        try {
            List<String> dexNames = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            int maxN = 1;
            while (e.hasMoreElements()) { String n = e.nextElement().getName(); if (n.matches("classes\\d*\\.dex")) { dexNames.add(n); maxN = Math.max(maxN, num(n)); } }
            Collections.sort(dexNames, (a, b) -> num(a) - num(b));
            e = zf.entries();
            int di = 0;
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement(); String n = en.getName();
                if (n.equals("META-INF/MANIFEST.MF") || n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".SF") || n.endsWith(".EC"))) continue; // drop old sig
                byte[] data;
                if (n.matches("classes\\d*\\.dex")) { data = dexes.get(num(n) == 1 ? indexOf(dexNames, "classes.dex") : indexOf(dexNames, n)); }
                else if (n.equals("AndroidManifest.xml") && patchedManifest != null) { data = patchedManifest; }
                else { data = readAll(zf.getInputStream(en)); }
                zo.putNextEntry(new ZipEntry(n)); zo.write(data); zo.closeEntry();
            }
            // append HudEvents as classes(maxN+1).dex
            zo.putNextEntry(new ZipEntry("classes" + (maxN + 1) + ".dex")); zo.write(hudDex); zo.closeEntry();
        } finally { zo.close(); zf.close(); }
    }
    private static int indexOf(List<String> l, String s) { int i = l.indexOf(s); return i < 0 ? 0 : i; }
}
