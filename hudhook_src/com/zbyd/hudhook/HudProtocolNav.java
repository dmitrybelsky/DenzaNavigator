package com.zbyd.hudhook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

/**
 * EXPERIMENTAL — drives the stock BYD map (launchermap/Amap) to navigate to a destination THROUGH a via
 * point, via its exported ungated voice-protocol service. Amap then builds a real HD route (passing the
 * via) and publishes it to the native ADAS, which runs NOA along it — so NOA hugs the Yandex path WITHOUT
 * synthesizing HD data (which the firmware rejects).
 *
 * Bind: com.byd.launchermap / com.autosdk.protocol.service.ProtocolService (action ..ProtocolService),
 * onBind() returns an IProtocolAidlInterface.Stub. We raw-transact setProtocolModelData (tx 1) with a
 * ProtocolBaseModel parcel (protocolID 30406 = navi-opera). The base model carries dest* and pass* (one
 * via) directly — no json needed for a single via. Field order reversed from ProtocolBaseModel.writeToParcel.
 *
 * actionType / operaType for "start route navigation" are not certain from the decompile — try a couple
 * live. NOA still does camera/radar-vetoed driving; closed/empty road, driver supervising.
 */
public final class HudProtocolNav {

    private static final String PKG = "com.byd.launchermap";
    private static final String SVC = "com.autosdk.protocol.service.ProtocolService";
    private static final String ACTION = "action.com.autosdk.protocol.ProtocolService";
    private static final String IFACE = "com.autosdk.protocol.IProtocolAidlInterface";
    private static final int TX_SET_MODEL = 1;
    private static final int PROTOCOL_NAVI_OPERA = 30406;
    private static final int ACTION_NAVIGATE = 1;            // best-guess; verify live

    private static volatile IBinder sBinder;
    private static volatile boolean sBinding;

    /** Navigate to (dlat,dlon) passing through one via (plat,plon). via* may be the route midpoint. */
    public static void navigate(final Context ctx, final double dlat, final double dlon, final String dname,
                                final double plat, final double plon, final String pname) {
        if (ctx == null) return;
        IBinder b = sBinder;
        if (b != null) { send(b, dlat, dlon, dname, plat, plon, pname); return; }
        if (sBinding) return; sBinding = true;
        try {
            Intent it = new Intent(ACTION).setComponent(new ComponentName(PKG, SVC));
            ctx.getApplicationContext().bindService(it, new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName n, IBinder bb) {
                    sBinder = bb; sBinding = false;
                    send(bb, dlat, dlon, dname, plat, plon, pname);
                }
                @Override public void onServiceDisconnected(ComponentName n) { sBinder = null; }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) { sBinding = false; HudLog.f("ProtocolNav bind fail: " + t); }
    }

    private static void send(IBinder b, double dlat, double dlon, String dname,
                             double plat, double plon, String pname) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(IFACE);
            d.writeInt(1);                                   // model != null
            // ProtocolBaseModel.writeToParcel field order:
            d.writeInt(PROTOCOL_NAVI_OPERA);                 // protocolID
            d.writeLong(System.currentTimeMillis());         // timeStamp
            d.writeInt(0);                                    // callbackId
            d.writeString("1");                               // modelVersion
            d.writeString("com.zbyd.hudhook");                // packageName
            d.writeString("");                                // var1
            d.writeInt(ACTION_NAVIGATE);                      // actionType
            d.writeInt(0);                                    // operaType
            d.writeString("");                                // searchKey
            d.writeString(dname == null ? "" : dname);        // destPoiName
            d.writeInt(0);                                    // errorCode
            d.writeString(String.valueOf(dlat));              // destLatitude
            d.writeString(String.valueOf(dlon));              // destLongitude
            d.writeString(pname == null ? "" : pname);        // passPoiName
            d.writeString(plat == 0 ? "" : String.valueOf(plat));  // passLatitude
            d.writeString(plon == 0 ? "" : String.valueOf(plon));  // passLongitude
            d.writeInt(0);                                    // searchQueryType
            b.transact(TX_SET_MODEL, d, r, 0);
            try { r.readException(); } catch (Throwable e) {}
            HudLog.f("ProtocolNav navigate dest=" + dlat + "," + dlon + " via=" + plat + "," + plon);
        } catch (Throwable t) { sBinder = null; HudLog.f("ProtocolNav send fail: " + t); }
        finally { r.recycle(); d.recycle(); }
    }
}
