package com.byd.cluster.projection.mapdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.io.DataInputStream;

/**
 * Cluster-map bridge service. (1) Listens on abstract LocalSocket "zbyd_cluster_map" for length-
 * prefixed PNG frames streamed by the patched Yandex, decoding the latest into FrameHolder.
 * (2) Requests projection from the BYD cluster ProjectionService (we are the whitelisted "mapdemo"
 * package in debug mode) so it launches our MapViewActivity onto the cluster display, which then
 * renders FrameHolder. A minimal IContentProjectionCallback stub answers readyForProjection=true.
 */
public final class ClusterMapService extends Service {

    private static final String TAG = "ZBYD_CLUSTERMAP";
    private static final String SOCK = "zbyd_cluster_map";
    private static final String CB_DESC = "com.byd.cluster.projectionmanager.service.IContentProjectionCallback";
    private static final String MGR_DESC = "com.byd.cluster.projectionmanager.service.IContentProjectionManager";
    private static final int TX_REGISTER_CB = 1, TX_START = 3;
    // enum ordinals
    private static final int POS_FULL = 0, POS_RIGHT = 2;
    private static final int TYPE_MAP_VIEW = 4, TYPE_MINI_MAP = 5;

    private volatile IBinder mMgr;
    private volatile boolean mRunning;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mRunning) {
            mRunning = true;
            foreground();
            new Thread(new Runnable() { @Override public void run() { serveSocket(); } }, "clustermap-sock").start();
            bindProjection();
        }
        return START_STICKY;
    }

    private void foreground() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("zbyd", "ClusterMap", NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, "zbyd").setContentTitle("ClusterMap")
                    .setSmallIcon(android.R.drawable.ic_menu_mapmode).build();
            startForeground(1, n);
        } catch (Throwable t) { Log.w(TAG, "fg: " + t); }
    }

    private void serveSocket() {
        while (mRunning) {
            try {
                LocalServerSocket srv = new LocalServerSocket(SOCK);
                Log.i(TAG, "listening @" + SOCK);
                while (mRunning) {
                    LocalSocket s = srv.accept();
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    try {
                        while (mRunning) {
                            int len = in.readInt();
                            if (len <= 0 || len > 8 * 1024 * 1024) break;
                            byte[] buf = new byte[len];
                            in.readFully(buf);
                            Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len);
                            if (bmp != null) FrameHolder.set(bmp);
                        }
                    } catch (Throwable e) { /* client gone */ }
                    try { s.close(); } catch (Throwable e) {}
                }
            } catch (Throwable t) { Log.w(TAG, "sock: " + t); sleep(1000); }
        }
    }

    // ---- projection request ----------------------------------------------------------------
    private void bindProjection() {
        if (tryBind("com.byd.projection.management", "com.byd.projection.management.service.BYDProjectionService")) return;
        if (tryBind("com.example.amapservice", "com.byd.cluster.projectionmanager.service.ProjectionService")) return;
        tryBind("com.example.amapservice", "com.byd.cluster.projectionmanager.service.BydProjectionService");
    }

    private boolean tryBind(String pkg, String cls) {
        try {
            Intent it = new Intent();
            it.setComponent(new ComponentName(pkg, cls));
            it.putExtra("user", "mapdemo");
            boolean ok = bindService(it, mConn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bind " + pkg + "=" + ok);
            return ok;
        } catch (Throwable t) { Log.w(TAG, "bind " + pkg + ": " + t); return false; }
    }

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            mMgr = b;
            registerCallback();
            // try the right-side mini-map first, then full map
            int r1 = startProjection(POS_RIGHT, TYPE_MINI_MAP);
            int r2 = (r1 == 0) ? 0 : startProjection(POS_FULL, TYPE_MAP_VIEW);
            Log.i(TAG, "projection connected, right=" + r1 + " full=" + r2);
        }
        @Override public void onServiceDisconnected(ComponentName n) { mMgr = null; }
    };

    private final IBinder mCallback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == TX_REGISTER_CB || code == 1) {                 // readyForProjection(int,int)->bool
                data.enforceInterface(CB_DESC);
                int a = data.readInt(), b = data.readInt();
                Log.i(TAG, "readyForProjection(" + a + "," + b + ")=true");
                if (reply != null) { reply.writeNoException(); reply.writeInt(1); }
                return true;
            }
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CB_DESC);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private void registerCallback() {
        IBinder m = mMgr; if (m == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(MGR_DESC);
            d.writeStrongBinder(mCallback);
            m.transact(TX_REGISTER_CB, d, r, 0);
            r.readException();
            Log.i(TAG, "registerCallback ok");
        } catch (Throwable t) { Log.w(TAG, "registerCallback: " + t); }
        finally { r.recycle(); d.recycle(); }
    }

    private int startProjection(int pos, int type) {
        IBinder m = mMgr; if (m == null) return -100;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(MGR_DESC);
            d.writeInt(pos); d.writeInt(type);
            m.transact(TX_START, d, r, 0);
            r.readException();
            int ret = r.readInt();
            Log.i(TAG, "startContentProjection(" + pos + "," + type + ")=" + ret);
            return ret;
        } catch (Throwable t) { Log.w(TAG, "startProjection: " + t); return -200; }
        finally { r.recycle(); d.recycle(); }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (Throwable e) {} }

    @Override public void onDestroy() { mRunning = false; super.onDestroy(); }
}
