package com.zbyd.bootstrap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * On-boot (or manual) bootstrap: connect to the local adbd via AdbClient and run the boot script as
 * SHELL uid — restarting the autoservice helper (and anything else in /data/local/tmp/zbyd_boot.sh)
 * so the integration survives a car reboot without a PC. First run prompts to authorize debugging on
 * the car (one-time). Retries while adbd comes up.
 */
public final class BootstrapService extends Service {
    private static final String TAG = "ZBYD_BOOT";
    private static final String SCRIPT = "sh /data/local/tmp/zbyd_boot.sh";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        fg();
        new Thread(new Runnable() { @Override public void run() { runBootstrap(); } }, "zbyd-boot").start();
        return START_NOT_STICKY;
    }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final String CAMERA = "com.byd.cameraautostudy";

    private void runBootstrap() {
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                String out = new AdbClient(getFilesDir()).shell(HOST, PORT, SCRIPT);   // AutoHelper + masquerade
                log("boot script: " + out);
                try { injectAgent(); } catch (Throwable t) { log("agent inject fail: " + t); }  // best-effort
                stopSelf(); return;
            } catch (Throwable t) {
                Log.w(TAG, "attempt " + attempt + ": " + t);
                log("attempt " + attempt + ": " + t);
                try { Thread.sleep(10000); } catch (Throwable e) {}
            }
        }
        stopSelf();
    }

    /** Re-inject the privileged agent (HudPrivAgent.dex) into cameraautostudy via JDWP-over-ADB.
     *  The dex persists in /data/local/tmp across reboot (last PC deploy); cameraautostudy (platform_app)
     *  reads those bytes for the in-memory load. MANAGE_EXTERNAL_STORAGE is (re-)granted so the /sdcard
     *  file bridge works. The app is poked with a config change (cmd uimode) so its MAIN thread dispatches
     *  a Handler message -> a thread suspended at a Java safe point for the injector. */
    private void injectAgent() throws Exception {
        sh("appops set " + CAMERA + " MANAGE_EXTERNAL_STORAGE allow");
        sh("appops set ru.yandex.yandexnavi MANAGE_EXTERNAL_STORAGE allow");
        String pid = sh("pidof " + CAMERA).trim();
        if (pid.isEmpty()) { sh("am start -n " + CAMERA + "/.CameraAutoStudyTest"); Thread.sleep(1500); pid = sh("pidof " + CAMERA).trim(); }
        if (pid.isEmpty()) { log("no " + CAMERA + " pid"); return; }
        final String fpid = pid.split("\\s+")[0];
        final boolean[] done = {false};
        Thread poke = new Thread(new Runnable() { @Override public void run() {
            try { AdbClient pc = new AdbClient(getFilesDir()); pc.connect(HOST, PORT);
                boolean night = false;
                while (!done[0]) {
                    night = !night;
                    try { AdbClient.AdbStream ps = pc.open("shell:cmd uimode night " + (night ? "yes" : "no"));
                          while (ps.readChunk() != null) {} } catch (Throwable t) {}
                    Thread.sleep(700);
                }
            } catch (Throwable t) {}
        }}, "zbyd-poke");
        poke.start();
        try {
            AdbClient jc = new AdbClient(getFilesDir()); jc.connect(HOST, PORT);
            AdbClient.AdbStream js = jc.open("jdwp:" + fpid);
            String r = new JdwpInject(js).inject("/data/local/tmp/HudPrivAgent.dex", null, "com.zbyd.hudpriv.HudPrivAgent");
            log("agent inject: " + r);
        } finally { done[0] = true; }
    }

    private String sh(String cmd) throws Exception { return new AdbClient(getFilesDir()).shell(HOST, PORT, cmd); }

    private void fg() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("zbydboot", "Bootstrap", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, "zbydboot").setContentTitle("zbyd bootstrap")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).build();
            startForeground(7, n);
        } catch (Throwable t) {}
    }

    private void log(String m) {
        try { java.io.FileWriter w = new java.io.FileWriter(new java.io.File(getFilesDir(), "bootstrap.log"), true);
              w.write(System.currentTimeMillis() + " " + m + "\n"); w.close(); } catch (Throwable e) {}
    }
}
