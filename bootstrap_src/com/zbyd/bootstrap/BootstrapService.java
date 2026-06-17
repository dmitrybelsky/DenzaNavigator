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

    private void runBootstrap() {
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                AdbClient adb = new AdbClient(getFilesDir());
                String out = adb.shell("127.0.0.1", 5555, SCRIPT);
                Log.i(TAG, "bootstrap ok: " + out);
                log("bootstrap ok: " + out);
                stopSelf(); return;
            } catch (Throwable t) {
                Log.w(TAG, "attempt " + attempt + ": " + t);
                log("attempt " + attempt + ": " + t);
                try { Thread.sleep(10000); } catch (Throwable e) {}
            }
        }
        stopSelf();
    }

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
