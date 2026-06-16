package com.zbyd.patcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * zbyd Yandex on-device patcher. Reads the installed Yandex Navi base.apk (world-readable), re-applies
 * the HUD mod via dexlib2 (PatchEngine) + AXML (AxmlPatch) + bundled HudEvents.dex + apksig (ApkSign),
 * logs every step (so a target moved by a new version is reported, not fatal), then offers to install.
 * Resource-table patches (passport account-type, ad-domain NSC) need a full resource rebuild and are
 * logged as SKIPPED — do those on the PC toolchain if needed (the manifest ad-provider strip covers most).
 */
public final class MainActivity extends Activity {

    private static final String TARGET = "ru.yandex.yandexnavi";
    private static final String ADS_PROVIDER = "MobileAdsInitializeProvider";
    private TextView logView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        Button go = new Button(this); go.setText("Патчить установленный Яндекс.Навигатор");
        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { v.setEnabled(false); patchAsync(); } });
        root.addView(go);
        ScrollView sv = new ScrollView(this); logView = new TextView(this); logView.setTextSize(11);
        logView.setPadding(16, 16, 16, 16); logView.setTextIsSelectable(true);
        sv.addView(logView); root.addView(sv);
        setContentView(root);
        log("zbyd patcher ready. Target: " + TARGET);
    }

    private void log(String s) {
        logBuf.append(s).append('\n');
        main.post(() -> logView.setText(logBuf.toString()));
        try { FileOutputStream f = new FileOutputStream(new File(getExternalFilesDir(null), "patch.log"), false);
            f.write(logBuf.toString().getBytes("UTF-8")); f.close(); } catch (Throwable t) {}
    }

    private void patchAsync() { new Thread(this::patch).start(); }

    private void patch() {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(TARGET, 0);
            File inApk = new File(ai.sourceDir);
            log("[*] source: " + inApk + " (" + inApk.length() / 1024 / 1024 + " MB)");
            if (!inApk.canRead()) { log("[x] base.apk not readable — abort"); return; }

            // 1) dex patches (signature-based)
            PatchEngine eng = new PatchEngine(this::log);
            List<byte[]> dexes = eng.patchDexes(inApk);

            // 2) manifest: debuggable + strip ads provider (AXML)
            byte[] manifest = null;
            try {
                byte[] axml = readZip(inApk, "AndroidManifest.xml");
                manifest = new AxmlPatch().patch(axml, ADS_PROVIDER);
                log("[+] manifest: debuggable=true + ads-provider stripped");
            } catch (Throwable t) { log("[!] manifest AXML patch failed: " + t + " — install may hit anti-tamper without debuggable"); }
            detectPassportConflict();
            log("[!] ad-domain NSC: SKIPPED (resource rebuild; ad-provider strip covers most ads)");

            // 3) bundled HudEvents.dex
            byte[] hud = readAsset("HudEvents.dex");
            log("[+] HudEvents.dex bundled (" + hud.length + " B)");

            // 4) repackage (unsigned) + re-sign
            File cache = getCacheDir();
            File unsigned = new File(cache, "yn-unsigned.apk");
            eng.repackage(inApk, dexes, hud, manifest, unsigned);
            log("[*] repackaged: " + unsigned.length() / 1024 / 1024 + " MB");

            File outApk = new File(getExternalFilesDir(null), "yandexnavi-zbyd-patched.apk");
            if (outApk.exists()) outApk.delete();
            try (InputStream ks = getAssets().open("zbyd.p12")) {
                ApkSign.sign(ks, "android".toCharArray(), "zbyd", unsigned, outApk);
            }
            log("[ok] SIGNED: " + outApk);
            log("[i] Установка: 1) удалите штатный Навигатор (другая подпись)");
            log("[i] 2) откройте файл-менеджером и установите:");
            log("    " + outApk.getAbsolutePath());
            log("[i] либо: adb install -r -d \"" + outApk.getAbsolutePath() + "\"");
            log("[i] не забудьте на авто: appops set " + TARGET + " android:mock_location allow");
        } catch (Throwable t) {
            log("[x] FATAL: " + t);
            for (StackTraceElement e : t.getStackTrace()) log("    at " + e);
        }
    }

    /** Login works re-signed only if no OTHER (Yandex-signed) app owns the com.yandex.passport authenticator. */
    private void detectPassportConflict() {
        try {
            android.accounts.AuthenticatorDescription[] auths = android.accounts.AccountManager.get(this).getAuthenticatorTypes();
            String owner = null;
            for (android.accounts.AuthenticatorDescription a : auths)
                if ("com.yandex.passport".equals(a.type) && !TARGET.equals(a.packageName)) owner = a.packageName;
            if (owner != null) log("[!] passport: authenticator 'com.yandex.passport' owned by " + owner
                    + " — re-signed login WILL fail. Снеси этот пакет ИЛИ сделай account-type namespace на ПК (patch_yandex.sh).");
            else log("[+] passport: no conflicting authenticator — re-signed login should work (our app owns the type).");
        } catch (Throwable t) { log("[!] passport check failed: " + t); }
    }

    private byte[] readAsset(String n) throws Exception { return readAll(getAssets().open(n)); }
    private static byte[] readZip(File apk, String entry) throws Exception {
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk);
        try { return readAll(z.getInputStream(z.getEntry(entry))); } finally { z.close(); }
    }
    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n); in.close(); return o.toByteArray();
    }
}
