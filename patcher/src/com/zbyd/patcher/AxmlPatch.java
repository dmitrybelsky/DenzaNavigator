package com.zbyd.patcher;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal binary-AndroidManifest (AXML) editor for exactly the edits the mod needs, done by rebuilding
 * the string pool + resource map with a correct global re-index (so framework attrs resolve):
 *   - add android:debuggable="true" to <application>  (REQUIRED: bypasses 29.4.2 anti-tamper)
 *   - remove <provider> elements whose android:name contains a marker (ads init provider)
 * Returns patched AXML bytes, or throws (caller logs the failure — never silently wrong).
 *
 * AXML layout: [header 8][stringPool][resMap][xml chunks...]. Strings 0..M-1 are resource-mapped
 * (parallel resMap of resIds); attribute NAME indices resolve to framework attrs via resMap.
 */
public final class AxmlPatch {

    private static final int RES_DEBUGGABLE = 0x0101000f;
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private byte[] b;
    private int spStart, spEnd, rmStart, rmEnd;
    private List<String> strings = new ArrayList<>();
    private int strCount, styleCount, strFlags, strDataOff, stylesOff;
    private List<Integer> resMap = new ArrayList<>();

    public byte[] patch(byte[] axml, String adsProviderMarker) throws Exception {
        this.b = axml.clone();
        parsePools();
        // 1) ensure "debuggable" + its resId exist in the resource-mapped region (index = M)
        int dbgIdx = indexOf("debuggable");
        boolean reindexNeeded = false;
        int M = resMap.size();
        if (dbgIdx < 0 || dbgIdx >= M) {
            // insert "debuggable" at position M, append resId; shift refs >= M by +1
            strings.add(M, "debuggable");
            resMap.add(RES_DEBUGGABLE);
            reindexNeeded = true;
            dbgIdx = M;
        }
        int trueIdx = indexOf("true"); if (trueIdx < 0) { strings.add("true"); trueIdx = strings.size() - 1; }
        int nsIdx = indexOf(ANDROID_NS);
        // rebuild file with new pools, reindexing all string refs in the XML body
        byte[] body = sliceXmlBody();
        if (reindexNeeded) body = reindexBody(body, M, 1);
        // 2) edit XML body: add debuggable attr to <application>, strip ads provider
        body = editBody(body, dbgIdx, trueIdx, nsIdx, adsProviderMarker);
        return assemble(body);
    }

    // ---------- parse ----------
    private int u16(int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private int u32(int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24); }

    private void parsePools() throws Exception {
        if (u16(0) != 0x0003) throw new Exception("not AXML");
        int o = 8;
        // string pool
        if (u16(o) != 0x0001) throw new Exception("no string pool");
        spStart = o; int spSize = u32(o + 4); spEnd = o + spSize;
        strCount = u32(o + 8); styleCount = u32(o + 12); strFlags = u32(o + 16);
        strDataOff = u32(o + 20); stylesOff = u32(o + 24);
        boolean utf8 = (strFlags & 0x100) != 0;
        int offArr = o + 28;
        strings.clear();
        for (int i = 0; i < strCount; i++) {
            int so = o + strDataOff + u32(offArr + i * 4);
            strings.add(utf8 ? readUtf8(so) : readUtf16(so));
        }
        // resource map (optional)
        o = spEnd;
        resMap.clear();
        if (o + 8 <= b.length && u16(o) == 0x0180) {
            rmStart = o; int rmSize = u32(o + 4); rmEnd = o + rmSize;
            int cnt = (rmSize - 8) / 4;
            for (int i = 0; i < cnt; i++) resMap.add(u32(o + 8 + i * 4));
        } else { rmStart = rmEnd = o; }
    }

    private String readUtf16(int o) { int len = u16(o); o += 2; if (len >= 0x8000) { len = ((len & 0x7fff) << 16) | u16(o); o += 2; }
        char[] c = new char[len]; for (int i = 0; i < len; i++) c[i] = (char) u16(o + i * 2); return new String(c); }
    private String readUtf8(int o) { int l1 = b[o] & 0xff; o += (l1 >= 0x80) ? 2 : 1; int l2 = b[o] & 0xff; int len = l2; o += (l2 >= 0x80) ? 2 : 1;
        return new String(b, o, len, java.nio.charset.StandardCharsets.UTF_8); }

    private int indexOf(String s) { return strings.indexOf(s); }
    private byte[] sliceXmlBody() { int s = rmEnd; byte[] r = new byte[b.length - s]; System.arraycopy(b, s, r, 0, r.length); return r; }

    // ---------- reindex string refs in XML body by +delta for refs >= from ----------
    private byte[] reindexBody(byte[] body, int from, int delta) {
        int o = 0;
        while (o + 8 <= body.length) {
            int type = u16b(body, o); int size = u32b(body, o + 4);
            if (size <= 0 || o + size > body.length) break;
            switch (type) {
                case 0x0100: case 0x0101: // start/end namespace: prefix(o+16), uri(o+20)
                    bump(body, o + 16, from, delta); bump(body, o + 20, from, delta); break;
                case 0x0102: { // start element
                    bump(body, o + 16, from, delta); // ns
                    bump(body, o + 20, from, delta); // name
                    int attrCount = u16b(body, o + 28);
                    int attrStart = o + 36;
                    for (int i = 0; i < attrCount; i++) { int a = attrStart + i * 20;
                        bump(body, a, from, delta);      // ns
                        bump(body, a + 4, from, delta);  // name
                        bump(body, a + 8, from, delta);  // rawValue (string idx)
                        if ((body[a + 15] & 0xff) == 0x03) bump(body, a + 16, from, delta); // TYPE_STRING: typed data is ALSO a string idx
                    }
                    break; }
                case 0x0103: bump(body, o + 16, from, delta); bump(body, o + 20, from, delta); break; // end element
                case 0x0104: bump(body, o + 16, from, delta); break; // cdata
            }
            o += size;
        }
        return body;
    }
    private void bump(byte[] d, int o, int from, int delta) { int v = u32b(d, o); if (v != -1 && v >= from) put32(d, o, v + delta); }

    // ---------- edit: add debuggable to <application>, remove ads provider + foreign <permission> ----------
    private byte[] editBody(byte[] body, int dbgIdx, int trueIdx, int nsIdx, String adsMarker) {
        // pass 0: package name + perms referenced by android:permission/read/writePermission
        String pkg = ""; java.util.Set<String> referenced = new java.util.HashSet<>();
        { int o = 0;
          while (o + 8 <= body.length) { int type = u16b(body, o); int size = u32b(body, o + 4); if (size <= 0) break;
            if (type == 0x0102) {
                int nameIdx = u32b(body, o + 20); String nm = nameIdx >= 0 && nameIdx < strings.size() ? strings.get(nameIdx) : "";
                if (nm.equals("manifest")) { String p = getAttr(body, o, "package"); if (p != null) pkg = p; }
                for (String an : new String[]{"permission", "readPermission", "writePermission", "targetPermission"}) {
                    String v = getAttr(body, o, an); if (v != null) referenced.add(v);
                }
            }
            o += size; } }
        // pass 1: collect provider(ads) + foreign <permission> element ranges to drop
        List<int[]> drop = new ArrayList<>();
        int o = 0, appStart = -1;
        while (o + 8 <= body.length) {
            int type = u16b(body, o); int size = u32b(body, o + 4); if (size <= 0) break;
            if (type == 0x0102) { int nameIdx = u32b(body, o + 20);
                String nm = nameIdx >= 0 && nameIdx < strings.size() ? strings.get(nameIdx) : "";
                if (nm.equals("application")) appStart = o;
                if (nm.equals("provider") && adsMarker != null) {
                    if (providerNameContains(body, o, adsMarker)) {
                        int end = matchEnd(body, o); if (end > 0) drop.add(new int[]{o, end});
                    }
                }
                if (nm.equals("permission")) {   // strip FOREIGN defs (not under pkg AND not self-referenced)
                    String pn = getAttr(body, o, "name");
                    boolean own = pn != null && !pkg.isEmpty() && (pn.equals(pkg) || pn.startsWith(pkg + "."));
                    if (pn != null && !own && !referenced.contains(pn)) {
                        int end = matchEnd(body, o); if (end > 0) drop.add(new int[]{o, end});
                    }
                }
            }
            o += size;
        }
        // pass 2: splice out dropped provider ranges (from the end)
        for (int i = drop.size() - 1; i >= 0; i--) { int[] r = drop.get(i); body = splice(body, r[0], r[1]); if (appStart > r[0]) appStart -= (r[1] - r[0]); }
        // pass 3: add debuggable attr to <application>
        if (appStart >= 0) body = addAttr(body, appStart, nsIdx, dbgIdx, trueIdx);
        return body;
    }

    /** string value of attribute named attrName on a START element, or null. */
    private String getAttr(byte[] body, int elemOff, String attrName) {
        int attrCount = u16b(body, elemOff + 28); int attrStart = elemOff + 36;
        for (int i = 0; i < attrCount; i++) { int a = attrStart + i * 20; int nameIdx = u32b(body, a + 4);
            String an = nameIdx >= 0 && nameIdx < strings.size() ? strings.get(nameIdx) : "";
            if (an.equals(attrName)) { int raw = u32b(body, a + 8); return raw >= 0 && raw < strings.size() ? strings.get(raw) : null; } }
        return null;
    }

    private boolean providerNameContains(byte[] body, int elemOff, String marker) {
        int attrCount = u16b(body, elemOff + 28); int attrStart = elemOff + 36;
        for (int i = 0; i < attrCount; i++) { int a = attrStart + i * 20; int nameIdx = u32b(body, a + 4);
            String an = nameIdx >= 0 && nameIdx < strings.size() ? strings.get(nameIdx) : "";
            if (an.equals("name")) { int raw = u32b(body, a + 8); String v = raw >= 0 && raw < strings.size() ? strings.get(raw) : "";
                return v.contains(marker); } }
        return false;
    }

    /** find matching END element for a START element at off (balanced by name). */
    private int matchEnd(byte[] body, int startOff) {
        int nameIdx = u32b(body, startOff + 20); int depth = 0; int o = startOff;
        while (o + 8 <= body.length) { int type = u16b(body, o); int size = u32b(body, o + 4); if (size <= 0) break;
            if (type == 0x0102 && u32b(body, o + 20) == nameIdx) depth++;
            else if (type == 0x0103 && u32b(body, o + 20) == nameIdx) { depth--; if (depth == 0) return o + size; }
            o += size; }
        return -1;
    }

    private byte[] splice(byte[] body, int from, int to) { byte[] r = new byte[body.length - (to - from)];
        System.arraycopy(body, 0, r, 0, from); System.arraycopy(body, to, r, from, body.length - to); return r; }

    /** insert one 20-byte attribute (ns, name=dbg, rawValue=-1, typed bool true) into a START element. */
    private byte[] addAttr(byte[] body, int elemOff, int nsIdx, int nameIdx, int trueIdx) {
        int attrCount = u16b(body, elemOff + 28); int attrStart = elemOff + 36;
        int insAt = attrStart + attrCount * 20;
        byte[] attr = new byte[20];
        put32(attr, 0, nsIdx); put32(attr, 4, nameIdx); put32(attr, 8, -1);   // rawValue none
        put16(attr, 12, 8); attr[14] = 0; attr[15] = 0x12;                      // size=8, type=INT_BOOLEAN
        put32(attr, 16, 0xffffffff);                                            // data = true
        byte[] r = new byte[body.length + 20];
        System.arraycopy(body, 0, r, 0, insAt); System.arraycopy(attr, 0, r, insAt, 20);
        System.arraycopy(body, insAt, r, insAt + 20, body.length - insAt);
        // bump element chunk size (+20) + attributeCount (+1)
        int sz = u32b(r, elemOff + 4); put32(r, elemOff + 4, sz + 20);
        put16(r, elemOff + 28, attrCount + 1);
        return r;
    }

    // ---------- assemble new file ----------
    private byte[] assemble(byte[] body) throws Exception {
        byte[] sp = buildStringPool();
        byte[] rm = buildResMap();
        int total = 8 + sp.length + rm.length + body.length;
        byte[] out = new byte[total];
        put16(out, 0, 0x0003); put16(out, 2, 8); put32(out, 4, total);
        int o = 8; System.arraycopy(sp, 0, out, o, sp.length); o += sp.length;
        System.arraycopy(rm, 0, out, o, rm.length); o += rm.length;
        System.arraycopy(body, 0, out, o, body.length);
        return out;
    }

    private byte[] buildStringPool() {
        boolean utf8 = (strFlags & 0x100) != 0;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) { offs[i] = data.size(); writeStr(data, strings.get(i), utf8); }
        while (data.size() % 4 != 0) data.write(0);
        int offArrSize = strings.size() * 4 + styleCount * 4;
        int headerSize = 28;
        int strDataStart = headerSize + offArrSize;
        int size = strDataStart + data.size();
        byte[] out = new byte[size];
        put16(out, 0, 0x0001); put16(out, 2, 28); put32(out, 4, size);
        put32(out, 8, strings.size()); put32(out, 12, styleCount); put32(out, 16, strFlags);
        put32(out, 20, strDataStart); put32(out, 24, 0);
        for (int i = 0; i < strings.size(); i++) put32(out, 28 + i * 4, offs[i]);
        byte[] db = data.toByteArray(); System.arraycopy(db, 0, out, strDataStart, db.length);
        return out;
    }
    private void writeStr(ByteArrayOutputStream o, String s, boolean utf8) {
        if (utf8) { byte[] u = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            o.write(s.length() & 0xff); o.write(u.length & 0xff); o.write(u, 0, u.length); o.write(0); }
        else { int len = s.length(); o.write(len & 0xff); o.write((len >> 8) & 0xff);
            for (int i = 0; i < len; i++) { char c = s.charAt(i); o.write(c & 0xff); o.write((c >> 8) & 0xff); } o.write(0); o.write(0); }
    }
    private byte[] buildResMap() {
        if (resMap.isEmpty()) return new byte[0];
        int size = 8 + resMap.size() * 4; byte[] out = new byte[size];
        put16(out, 0, 0x0180); put16(out, 2, 8); put32(out, 4, size);
        for (int i = 0; i < resMap.size(); i++) put32(out, 8 + i * 4, resMap.get(i));
        return out;
    }

    // ---------- LE helpers on arbitrary arrays ----------
    private static int u16b(byte[] d, int o) { return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8); }
    private static int u32b(byte[] d, int o) { return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24); }
    private static void put16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void put32(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); d[o + 2] = (byte) (v >> 16); d[o + 3] = (byte) (v >> 24); }
}
