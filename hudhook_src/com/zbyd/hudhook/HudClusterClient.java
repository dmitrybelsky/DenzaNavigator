package com.zbyd.hudhook;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.DataOutputStream;

/**
 * Streams the captured Yandex map raster to the cluster-map masquerade app (package
 * com.byd.cluster.projection.mapdemo) over the abstract LocalSocket "zbyd_cluster_map" as
 * length-prefixed PNG frames. That app projects them onto the instrument-cluster nav area via the
 * BYD ProjectionService. No-op (back-off) when the app isn't running (e.g. phone, debug off).
 */
public final class HudClusterClient {

    private static final String SOCK = "zbyd_cluster_map";
    private static volatile LocalSocket sSock;
    private static volatile DataOutputStream sOut;
    private static long sLastTry;
    private static int sLog;

    public static void send(byte[] png) {
        if (png == null || png.length == 0 || !connect()) return;
        try {
            sOut.writeInt(png.length);
            sOut.write(png);
            sOut.flush();
            if (sLog++ < 3) HudLog.f("HudClusterClient sent " + png.length + "B");
        } catch (Throwable t) { close(); }
    }

    private static synchronized boolean connect() {
        if (sOut != null) return true;
        long now = System.currentTimeMillis();
        if (now - sLastTry < 3000) return false;
        sLastTry = now;
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
            sSock = s; sOut = new DataOutputStream(s.getOutputStream());
            HudLog.f("HudClusterClient connected @" + SOCK);
            return true;
        } catch (Throwable t) { return false; }
    }

    private static void close() {
        try { sSock.close(); } catch (Throwable e) {}
        sSock = null; sOut = null;
    }
}
