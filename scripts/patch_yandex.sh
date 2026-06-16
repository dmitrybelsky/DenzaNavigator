#!/usr/bin/env bash
# Standard Yandex Navigator patching kit.
#
# Applies the full, repeatable patch set to a decompiled Yandex APK, then rebuilds + signs.
# All steps are idempotent (safe to re-run) and guarded (skip + warn if a target moved in a
# new version, instead of failing the whole build).
#
# Patch set:
#   1. k0.c()Z            -> return true   (bypass Yandex Plus paywall gate)
#   2. sz3/d.a()Z         -> return true   (bypass com.google.android.gms.permission.CAR_SPEED)
#   3. ad/analytics block -> res/xml/network_security_config.xml + manifest wiring
#                            (empty trust-anchors => TLS to telemetry hosts fails)
#   4. care_offer_order.xml -> drop gravity="0x0" (aapt2 build fix)
#
# Usage:
#   apktool d yandexnavi-<ver>-orig.apk -o /tmp/yandex-decompiled    # one-time decode
#   ./patch_yandex.sh [decoded_dir] [out_apk] [--no-build]
# Defaults: decoded_dir=/tmp/yandex-decompiled  out_apk=/tmp/yandexnavi-signed.apk
set -euo pipefail

DEC="${1:-/tmp/yandex-decompiled}"
OUT="${2:-/tmp/yandexnavi-signed.apk}"
NO_BUILD=0; [[ "${3:-}" == "--no-build" || "${2:-}" == "--no-build" ]] && NO_BUILD=1
SELF="$(cd "$(dirname "$0")" && pwd)"
KS="$SELF/zbyd-debug.keystore"
BT="/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

[ -d "$DEC" ] || { echo "[x] no decoded dir: $DEC  (run: apktool d <apk> -o $DEC)"; exit 1; }

echo "[*] patching $DEC"
python3 - "$DEC" "$SELF/patches/network_security_config.xml" <<'PY'
import sys, os, re, glob, shutil
dec, nsc_src = sys.argv[1], sys.argv[2]

def force_true(globpat, method_re, label):
    fs = glob.glob(os.path.join(dec, globpat))
    if not fs:
        print(f"[!] {label}: not found ({globpat}) -- skip (version diff?)"); return
    f = fs[0]; txt = open(f,encoding='utf-8',errors='surrogateescape').read()
    m = re.search(r'(\.method[^\n]*\b'+method_re+r'\n[ \t]*\.locals )(\d+)(\n)', txt)
    if not m:
        print(f"[!] {label}: method/.locals not matched -- skip"); return
    if txt[m.end():].lstrip().startswith('const/4 v0, 0x1'):
        print(f"[=] {label}: already patched"); return
    n = max(1, int(m.group(2)))
    inj = m.group(1)+str(n)+m.group(3)+"\n    const/4 v0, 0x1\n\n    return v0\n"
    open(f,'w',encoding='utf-8',errors='surrogateescape').write(txt[:m.start()] + inj + txt[m.end():])
    print(f"[+] {label}: patched -> return true ({os.path.relpath(f,dec)})")

force_true('smali_classes*/ru/yandex/yandexmaps/integrations/projected/k0.smali', r'c\(\)Z', 'k0.c paywall')
force_true('smali_classes*/sz3/d.smali', r'a\(\)Z', 'sz3.d CAR_SPEED')

# 3. ad/analytics block: drop NSC + wire manifest (skipped when decoded with -r = binary manifest)
#    Also strip <permission> *definitions* (not uses-permission): a re-signed standalone APK can't
#    co-own a shared Yandex permission already defined by another installed Yandex app (taxi/maps/...),
#    which otherwise fails install with INSTALL_FAILED_DUPLICATE_PERMISSION. The app only needs the
#    matching <uses-permission> to call them; the definition belongs to whoever installs first.
try:
    mf = os.path.join(dec,'AndroidManifest.xml'); man = open(mf, encoding='utf-8').read()
    if '<application' not in man:
        raise ValueError('binary/undecoded manifest')
    # 3a. ANTI-TAMPER BYPASS (29.4.2+): the app has a native signature/attestation guard
    #     (MapKit attestation_updater in libmaps-mobile.so) that silently restarts+exits a re-signed
    #     build before any UI. It is DISABLED when the app runs debuggable (dev builds are debuggable
    #     and would fail attestation, so the guard whitelists debug mode). Setting android:debuggable=
    #     "true" alone lets the re-signed APK run normally. Proven on 29.4.2 (was a hard wall otherwise).
    if 'android:debuggable="true"' in man:
        print("[=] anti-tamper bypass: already debuggable")
    else:
        man = man.replace('<application ', '<application android:debuggable="true" ', 1)
        print("[+] anti-tamper bypass: android:debuggable=\"true\" set (defeats 29.4.2 attestation guard)")
    # strip ONLY foreign collisions: keep self-enforced perms (name under app pkg OR referenced by an
    # android:permission/read/writePermission), else a provider/receiver requiring an now-undefined
    # permission throws SecurityException at init -> crash. Strip only cross-app shared defs (e.g.
    # com.yandex.permission.READ_CREDENTIALS owned by ru.yandex.taxi).
    pkg_m = re.search(r'<manifest\b[^>]*\bpackage="([^"]+)"', man)
    pkg = pkg_m.group(1) if pkg_m else ''
    referenced = set(re.findall(r'android:(?:permission|readPermission|writePermission|targetPermission)="([^"]+)"', man))
    defs = re.findall(r'<permission\b[^>]*?/>|<permission\b.*?</permission>', man, re.S)
    stripped = kept = 0
    for d in defs:
        nm = re.search(r'android:name="([^"]+)"', d)
        name = nm.group(1) if nm else ''
        own = pkg and (name == pkg or name.startswith(pkg + '.'))
        if (not own) and (name not in referenced):
            man = man.replace(d, ''); stripped += 1
            print(f"[+] dup-permission fix: stripped foreign <permission> {name}")
        else:
            kept += 1
    if stripped == 0:
        print(f"[=] dup-permission fix: no foreign <permission> to strip ({kept} kept)")
    else:
        print(f"[=] dup-permission fix: kept {kept} self/required definition(s)")
    xmldir = os.path.join(dec,'res','xml'); os.makedirs(xmldir, exist_ok=True)
    shutil.copyfile(nsc_src, os.path.join(xmldir,'network_security_config.xml'))
    if 'networkSecurityConfig' in man:
        print("[=] ad-block: manifest already references networkSecurityConfig")
    else:
        man = man.replace('<application ',
            '<application android:networkSecurityConfig="@xml/network_security_config" ', 1)
        print("[+] ad-block: NSC dropped + manifest wired")
    # 3c. REMOVE ADS: drop the Yandex Mobile Ads SDK App-Startup initializer provider so the ads SDK
    #     never initializes -> banner/promo ads don't load. Version-robust (provider name stable); the
    #     NSC ad-domain block is the network backstop. App ad-calls are lazy/guarded -> safe to skip init.
    ads = re.findall(r'<provider\b[^>]*MobileAdsInitializeProvider[^>]*?/>|<provider\b[^>]*MobileAdsInitializeProvider[^>]*?</provider>', man, re.S)
    if ads:
        for a in ads: man = man.replace(a, '')
        print(f"[+] remove-ads: stripped MobileAdsInitializeProvider ({len(ads)})")
    else:
        print("[=] remove-ads: ads initializer provider not found")
    open(mf,'w',encoding='utf-8').write(man)
except Exception as e:
    print(f"[!] manifest step: skip (manifest not text: {e}) -- decode without -r to enable")

# 3b. PASSPORT login fix: a re-signed standalone Navi can't add a system account of the SHARED type
#     "com.yandex.passport" — that AccountManager authenticator is owned by another (Yandex-signed)
#     Yandex app (taxi/maps/...), so addAccountExplicitly throws "cannot explicitly add accounts of
#     type: com.yandex.passport". Fix = namespace OUR account type (exactly how kinopoisk/autoru/skazbuka
#     use com.yandex.passport.<app>): rename the @string/passport_account_type resource. Both the
#     authenticator.xml declaration AND the runtime Account type derive from it (e0.b normalizes any
#     non-prod value back to "com.yandex.passport"+suffix), so a single resource edit is consistent and
#     makes our re-signed app the SOLE owner of "com.yandex.passport.zbyd" → addAccount succeeds.
import glob as _glob
acct = 0
for sx in _glob.glob(os.path.join(dec, 'res', 'values*', 'strings.xml')):
    t = open(sx, encoding='utf-8', errors='surrogateescape').read()
    if '"passport_account_type">com.yandex.passport<' in t:
        t = t.replace('"passport_account_type">com.yandex.passport<',
                      '"passport_account_type">com.yandex.passport.zbyd<')
        open(sx, 'w', encoding='utf-8', errors='surrogateescape').write(t); acct += 1
print(f"[+] passport login fix: account-type -> com.yandex.passport.zbyd ({acct} strings.xml)" if acct
      else "[=] passport login fix: passport_account_type not found / already namespaced")

# 4. care_offer_order.xml gravity fix (aapt2)
co = os.path.join(dec,'res','layout','care_offer_order.xml')
if os.path.exists(co):
    t = open(co).read()
    if 'gravity="0x0"' in t or 'gravity=\"0x0\"' in t:
        open(co,'w').write(re.sub(r'\s*android:gravity="0x0"', '', t))
        print("[+] care_offer_order: removed gravity=0x0")
    else:
        print("[=] care_offer_order: ok")

# 5. SPEED-LIMIT -> HudMap broadcast hook (YanaviHUD-style)
#    NaviKit pushes the road speed limit to
#    ru.yandex.yandexnavi.ui.guidance.speed.SpeedLimitView.setSpeedLimit(String).
#    We add HudHook.sl(view,text) which re-broadcasts it to HudMap (com.bilibili.bilithings),
#    and inject one invoke-static at the top of setSpeedLimit.
hook_smali = """.class public Lru/yandex/yandexnavi/hud/HudHook;
.super Ljava/lang/Object;

.method public static sl(Landroid/view/View;Ljava/lang/String;)V
    .locals 3

    if-eqz p0, :done

    :try_start
    invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
    move-result-object v0
    if-eqz v0, :done
    new-instance v1, Landroid/content/Intent;
    const-string v2, "ru.yandex.yandexnavi.HUD_SPEED_LIMIT"
    invoke-direct {v1, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
    const-string v2, "com.bilibili.bilithings"
    invoke-virtual {v1, v2}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;
    const-string v2, "limit"
    invoke-virtual {v1, v2, p1}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
    invoke-virtual {v0, v1}, Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch

    :done
    return-void

    :catch
    move-exception v0
    return-void
.end method
"""
hookdir = os.path.join(dec, 'smali', 'ru', 'yandex', 'yandexnavi', 'hud')
os.makedirs(hookdir, exist_ok=True)
open(os.path.join(hookdir, 'HudHook.smali'), 'w').write(hook_smali)

slv = glob.glob(os.path.join(dec, 'smali_classes*/ru/yandex/yandexnavi/ui/guidance/speed/SpeedLimitView.smali'))
if not slv:
    print("[!] speed-limit hook: SpeedLimitView.smali not found -- skip")
else:
    f = slv[0]; t = open(f).read()
    if 'HudHook' in t:
        print("[=] speed-limit hook: already injected")
    else:
        m = re.search(r'(\.method public setSpeedLimit\(Ljava/lang/String;\)V\n[ \t]*\.locals \d+\n)', t)
        if not m:
            print("[!] speed-limit hook: setSpeedLimit not matched -- skip")
        else:
            inj = m.group(1) + "\n    invoke-static {p0, p1}, Lru/yandex/yandexnavi/hud/HudHook;->sl(Landroid/view/View;Ljava/lang/String;)V\n"
            open(f,'w').write(t[:m.start()] + inj + t[m.end():])
            print("[+] speed-limit hook: HudHook added + setSpeedLimit injected")

# 6. GUIDANCE hook: stash the live NaviKit Guidance into HudEvents. The owner class is obfuscated
#    and MOVES per version (di/e in 18.4.0, di/h + u0 in 29.4.2), so locate by SIGNATURE — any
#    getGuidance()->Guidance with a move-result-object v0 — across all smali. Inject into all hits.
hooked = 0
sig = re.compile(r'(\.method [^\n]*getGuidance\(\)Lcom/yandex/navikit/guidance/Guidance;\n(?:.*\n)*?[ \t]*move-result-object v0\n)')
for f in glob.glob(os.path.join(dec, 'smali_classes*', '**', '*.smali'), recursive=True):
    t = open(f, encoding='utf-8', errors='surrogateescape').read()
    if 'getGuidance()Lcom/yandex/navikit/guidance/Guidance;' not in t:
        continue
    if 'HudEvents' in t:
        hooked += 1; continue
    m = sig.search(t)
    if not m:
        continue
    inj = m.group(1) + "\n    invoke-static {v0}, Lcom/zbyd/hudhook/HudEvents;->setGuidance(Ljava/lang/Object;)V\n"
    open(f, 'w', encoding='utf-8', errors='surrogateescape').write(t[:m.start()] + inj + t[m.end():])
    hooked += 1
    print(f"[+] guidance hook: HudEvents.setGuidance injected ({os.path.relpath(f, dec)})")
if hooked == 0:
    print("[!] guidance hook: no getGuidance()->Guidance match found -- skip")

# 7. ACTIVITY hook for the traffic-light OCR bridge: stash the nav Activity into HudTlOcr
#    (PixelCopy needs a Window). The launcher Activity moves per version: in 18.x it's
#    NavigatorActivity, in 29.x that's only an <activity-alias> -> ru.yandex.yandexmaps.app.MapActivity.
#    Try both, and hook a no-arg lifecycle method (onStart/onResume) so p0 is the Activity itself.
acts = (glob.glob(os.path.join(dec, 'smali_classes*', '**', 'NavigatorActivity.smali'), recursive=True)
        or glob.glob(os.path.join(dec, 'smali_classes*', 'ru', 'yandex', 'yandexmaps', 'app', 'MapActivity.smali')))
if not acts:
    print("[!] activity hook: no NavigatorActivity/MapActivity.smali -- skip (OCR countdown idle)")
else:
    f = acts[0]; t = open(f,encoding='utf-8',errors='surrogateescape').read()
    if 'HudTlOcr' in t:
        print("[=] activity hook: already injected")
    else:
        m = (re.search(r'(\.method (?:public |protected |final )+onStart\(\)V\n[ \t]*\.locals \d+\n)', t)
             or re.search(r'(\.method (?:public |protected |final )+onResume\(\)V\n[ \t]*\.locals \d+\n)', t))
        if not m:
            print("[!] activity hook: onStart/onResume not matched -- skip")
        else:
            inj = m.group(1) + "\n    invoke-static {p0}, Lcom/zbyd/hudhook/HudTlOcr;->setActivity(Landroid/app/Activity;)V\n"
            open(f,'w',encoding='utf-8',errors='surrogateescape').write(t[:m.start()] + inj + t[m.end():])
            print(f"[+] activity hook: HudTlOcr.setActivity injected ({os.path.relpath(f,dec)}::{m.group(1).split()[-1].split('(')[0]})")
PY

if [ "$NO_BUILD" = "1" ]; then echo "[*] --no-build: patches applied, skipping rebuild"; exit 0; fi

echo "[*] apktool b"; apktool b "$DEC" -o /tmp/yandexnavi-final.apk >/dev/null
# add the HudEvents helper dex (road-events) as the next contiguous classesN.dex
if [ -f "$SELF/patches/HudEvents.dex" ]; then
    maxn=1
    for e in $(unzip -l /tmp/yandexnavi-final.apk | grep -oE 'classes[0-9]*\.dex'); do
        num=$(echo "$e" | sed -E 's/classes//; s/\.dex//'); [ -z "$num" ] && num=1
        [ "$num" -gt "$maxn" ] && maxn=$num
    done
    NEXT=$((maxn + 1))
    cp "$SELF/patches/HudEvents.dex" "/tmp/classes${NEXT}.dex"
    (cd /tmp && zip -j /tmp/yandexnavi-final.apk "classes${NEXT}.dex" >/dev/null)
    rm -f "/tmp/classes${NEXT}.dex"
    echo "[+] road-events: added HudEvents as classes${NEXT}.dex"
fi
echo "[*] zipalign";  "$BT/zipalign" -p -f 4 /tmp/yandexnavi-final.apk /tmp/yandexnavi-aligned.apk
echo "[*] apksigner"; "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias zbyd --out "$OUT" /tmp/yandexnavi-aligned.apk
"$BT/apksigner" verify --print-certs "$OUT" | grep -i "CN=" | head -1
echo "[ok] signed patched APK: $OUT  ($(du -h "$OUT" | cut -f1))"
