package com.byd.cluster.projection.mapdemo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/** Rendered ON the cluster display (launched there by ProjectionService). Shows the latest streamed
 *  Yandex map frame, ~5 fps. Also ensures the bridge service runs so frames keep flowing. */
public class ClusterMapActivity extends Activity {
    private ImageView iv;
    private long lastSeq = -1;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFF000000);
        setContentView(iv);
        try { startForegroundService(new Intent(this, ClusterMapService.class)); } catch (Throwable t) {}
        h.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long s = FrameHolder.seq();
            if (s != lastSeq) {
                lastSeq = s;
                Bitmap bmp = FrameHolder.get();
                if (bmp != null) iv.setImageBitmap(bmp);
            }
            h.postDelayed(this, 200);
        }
    };

    @Override protected void onDestroy() { h.removeCallbacks(tick); super.onDestroy(); }
}
