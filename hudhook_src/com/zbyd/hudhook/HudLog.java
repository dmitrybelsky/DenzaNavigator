package com.zbyd.hudhook;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;

/**
 * File logger — Honor/MagicOS suppresses non-system logcat, so hook diagnostics go to the app's own
 * internal files dir (getFilesDir()/zbyd.log), readable non-root on a debuggable build via
 * `adb shell run-as ru.yandex.yandexnavi cat files/zbyd.log`. Best-effort, never throws.
 */
public final class HudLog {
    private static volatile File sFile;

    private static File file() {
        if (sFile == null) {
            try {
                Context c = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
                if (c != null) sFile = new File(c.getFilesDir(), "zbyd.log");
            } catch (Throwable t) {}
        }
        return sFile;
    }

    public static void f(String msg) {
        try {
            android.util.Log.i("zbydHUD", msg);   // also try logcat (in case it's visible)
            File f = file(); if (f == null) return;
            FileWriter w = new FileWriter(f, true);
            w.write(android.os.SystemClock.elapsedRealtime() + " " + msg + "\n");
            w.close();
        } catch (Throwable t) {}
    }
}
