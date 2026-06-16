package com.zbyd.hudhook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Phase 2: subscribe the car's SOME/IP positioning topics + read the BYDAuto HAL, fuse a jam-resistant
 * lane-accurate position, and push it into Yandex via HudLocation (android mock location). Car-only
 * (needs ts.car.someip.service + the BYDAuto HAL). Heavily logged (zbyd.log) for on-car bring-up.
 *
 * SOME/IP receive path (RE'd): bind client service "com.ts.car.someip.SomeIpClientService"
 * (desc ts.car.someip.sdk.ISomeIpClientInterface); registerCallback(cb)=TX1 (writeStrongBinder);
 * subscribe(topicId)=TX8 (writeLong). Events arrive at our ISomeIpCallback.onSomeIpEvent(SomeIpData)=TX1,
 * SomeIpData = {long topic, long timestamp, int len, byte[] payload}. Payload = protobuf struct
 * (often EG-wrapped at field 1). We extract double/varint fields by tag.
 */
public final class HudLocationCar {

    private static final String PKG = "com.ts.car.someip.service";
    private static final String CLIENT_ACTION = "com.ts.car.someip.SomeIpClientService";
    private static final String CLIENT_DESC = "ts.car.someip.sdk.ISomeIpClientInterface";
    private static final String CB_DESC = "ts.car.someip.sdk.ISomeIpCallback";
    private static final int TX_REGISTER_CALLBACK = 1, TX_SUBSCRIBE = 8;

    // topic ids (event ids) of the positioning notifies
    private static final long T_VEHICLE_POSITION = 1125942857203713L;
    private static final long T_INS             = 1125908698202113L;
    private static final long T_RTK             = 1125947152236545L;
    private static final long T_IMU             = 1125947152236546L;   // RTK_IMU service, IMU topic (gyro/accel)
    private static final long T_LANE_LINE       = 1125951447269378L;
    // NOTE: ADASISV2 (electronic horizon) + PILOT_ALARM (NOA) were dropped — both are bound to the
    // AMap/AutoNavi CHINA HD-map and produce nothing outside China (RF), so they're not subscribed.
    // VEHICLE_POSITION lat/lon/heading/speed are hardware GNSS+INS (work in RF); its hd* lane fields
    // (f16/f18/f20) are China-HD-map and unreliable in RF — used only opportunistically.
    private static final long[] TOPICS = { T_VEHICLE_POSITION, T_INS, T_RTK, T_IMU, T_LANE_LINE };

    private static volatile IBinder sBinder;
    private static volatile boolean sStarted;
    private static Context sCtx;

    // latest fused state
    private static volatile int sRtkStatus = -1;   // 4 = RTK fixed (best)
    private static volatile long sVehTs, sInsTs;
    public static volatile int sBlinker, sLaneId, sLaneNum;   // turn-signal + ego-lane (for lane confirm)
    private static volatile double sYawRate;                   // IMU gyro Z (turn rate)
    private static volatile double sLaneLat;                   // camera ego-lane lateral offset (m, +right)
    private static volatile boolean sLaneApply = false;        // apply lateral correction (off until c0 calibrated)

    public static void start(Context c) {
        if (sStarted || c == null) return;
        sStarted = true; sCtx = c.getApplicationContext();
        try {
            boolean ok = sCtx.bindService(new Intent(CLIENT_ACTION).setPackage(PKG), CONN, Context.BIND_AUTO_CREATE);
            HudLog.f("HudLocationCar bindService=" + ok);
        } catch (Throwable t) { HudLog.f("HudLocationCar bind fail: " + t); }
        startHal();
    }

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            sBinder = b; HudLog.f("HudLocationCar connected");
            try {
                registerCallback();
                for (long t : TOPICS) HudLog.f("subscribe " + t + " -> " + subscribe(t));
            } catch (Throwable t) { HudLog.f("HudLocationCar setup fail: " + t); }
        }
        @Override public void onServiceDisconnected(ComponentName n) { sBinder = null; }
    };

    private static void registerCallback() {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(CLIENT_DESC);
            d.writeStrongBinder(CALLBACK);
            sBinder.transact(TX_REGISTER_CALLBACK, d, r, 0); r.readException();
            HudLog.f("registerCallback ok");
        } catch (Throwable t) { HudLog.f("registerCallback fail: " + t); }
        finally { r.recycle(); d.recycle(); }
    }

    private static int subscribe(long topic) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try { d.writeInterfaceToken(CLIENT_DESC); d.writeLong(topic);
              sBinder.transact(TX_SUBSCRIBE, d, r, 0); r.readException(); return r.readInt(); }
        catch (Throwable t) { HudLog.f("subscribe " + topic + " fail: " + t); return -1; }
        finally { r.recycle(); d.recycle(); }
    }

    // our ISomeIpCallback.Stub — receives onSomeIpEvent(SomeIpData) at TX 1
    private static final Binder CALLBACK = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == TX_REGISTER_CALLBACK) {   // == onSomeIpEvent (TX 1)
                try {
                    data.enforceInterface(CB_DESC);
                    if (data.readInt() != 0) {           // SomeIpData present
                        long topic = data.readLong();
                        data.readLong();                  // timestamp
                        int len = data.readInt();
                        if (len >= 0) { byte[] payload = new byte[len]; data.readByteArray(payload); onEvent(topic, payload); }
                    }
                    if (reply != null) reply.writeNoException();
                } catch (Throwable t) { HudLog.f("onSomeIpEvent fail: " + t); }
                return true;
            }
            if (code == IBinder.INTERFACE_TRANSACTION) { reply.writeString(CB_DESC); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private static int sEvtLog;
    private static void onEvent(long topic, byte[] payload) {
        byte[] s = unwrap(payload);     // strip EG field-1 wrapper if present
        if (sEvtLog++ < 12) HudLog.f("evt topic=" + topic + " len=" + payload.length);
        try {
            if (topic == T_VEHICLE_POSITION) {
                double lon = pd(s, 3), lat = pd(s, 4), alt = pd(s, 5), head = pd(s, 6), spd = pd(s, 9);
                long laneId = pv(s, 16), laneNum = pv(s, 20); double laneOff = pd(s, 18);
                int blinker = (int) pv(s, 31);   // indicatorLight: 0=off,1=left,2=right,3=hazard
                sBlinker = blinker; sLaneId = (int) laneId; sLaneNum = (int) laneNum;
                if (lat != 0 && lat == lat) { sVehTs = now();
                    double[] c = applyLateral(lat, lon, head);   // camera lane-line lateral correction (RF)
                    // fused GNSS+INS+wheel-odo+IMU output -> rich fix (real altitude + nominal accuracies)
                    HudLocation.pushRich(c[0], c[1], alt, 1.0f, 2.0f, (float) head, 2.0f, (float) spd, 0.5f);
                    if (sEvtLog < 14) HudLog.f("VEH lat=" + lat + " lon=" + lon + " alt=" + alt + " head=" + head + " spd=" + spd + " lane=" + laneId + "/" + laneNum + " off=" + laneOff + " blink=" + blinker); }
                HudEvents.onCarLane(blinker, (int) laneId, (int) laneNum, laneOff);
            } else if (topic == T_RTK) {
                sRtkStatus = (int) pv(s, 3);
                if (sRtkStatus == 4) {   // RTK FIXED = cm precision; prefer it with REAL accuracies
                    double lon = pd(s, 6), lat = pd(s, 7), alt = pd(s, 8);
                    double lonAcc = pd(s, 9), latAcc = pd(s, 10), altAcc = pd(s, 11);
                    double headMove = pd(s, 12), headDual = pd(s, 13), headAcc = pd(s, 14);
                    double spd = pd(s, 15), spdAcc = pd(s, 16);
                    if (lat != 0 && lat == lat) { sVehTs = now();
                        float horiz = (float) Math.max(0.05, Math.max(latAcc, lonAcc));
                        // dual-antenna heading is valid even at standstill (phone GPS can't) -> use when ~stationary
                        double head = (spd < 0.5 && headDual != 0) ? headDual : headMove;
                        double[] c = applyLateral(lat, lon, head);
                        HudLocation.pushRich(c[0], c[1], alt, horiz, (float) Math.max(0.1, altAcc),
                            (float) head, (float) Math.max(0.5, headAcc), (float) spd, (float) Math.max(0.1, spdAcc));
                        if (sEvtLog < 14) HudLog.f("RTK FIX lat=" + lat + " alt=" + alt + " acc=" + horiz + " headDual=" + headDual + " spd=" + spd); }
                }
            } else if (topic == T_IMU) {
                double yawRate = pd(s, 5);   // angularVelocityZ (rad/s or deg/s) — turn-rate
                sYawRate = yawRate;
                if (sEvtLog < 14 && Math.abs(yawRate) > 0.05) HudLog.f("IMU yawRate=" + yawRate);
            } else if (topic == T_INS) {
                if (now() - sVehTs > 2000) {   // GNSS jammed -> INS dead-reckoning (wheel-odo + IMU fused)
                    double lat = pd(s, 7), lon = pd(s, 8), head = pd(s, 15), spd = pd(s, 25);
                    if (lat != 0 && lat == lat) { sInsTs = now();
                        HudLocation.pushRich(lat, lon, null, 5f, null, (float) head, 5f, (float) spd, 1f);
                        if (sEvtLog < 14) HudLog.f("INS(DR) lat=" + lat + " lon=" + lon + " head=" + head); }
                }
            } else if (topic == T_LANE_LINE) {
                // camera lane-line perception (works in RF). Extract ego lateral offset from the left/right
                // marking c0 distances. Line sub-message field layout is calibrated on car (log raw first).
                sLaneLat = parseLaneLateral(s);
                if (sEvtLog < 16) HudLog.f("LANE len=" + s.length + " egoLateral=" + sLaneLat + " (calibrate Line.c0 field on car)");
            }
        } catch (Throwable t) { HudLog.f("parse topic=" + topic + " fail: " + t); }
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    /** shift the fix laterally by the camera ego-lane offset (perpendicular to heading) for lane-level lateral
     *  precision. Bounded + gated (sLaneApply) — off until the Line.c0 field is calibrated on the car. */
    private static double[] applyLateral(double lat, double lon, double headDeg) {
        if (!sLaneApply || sLaneLat == 0 || Math.abs(sLaneLat) > 2.0) return new double[]{lat, lon};
        double perp = Math.toRadians(headDeg + 90.0);   // +right of travel
        double north = sLaneLat * Math.cos(perp), east = sLaneLat * Math.sin(perp);
        double dLat = north / 111320.0;
        double dLon = east / (111320.0 * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lon + dLon};
    }

    /** ego-lane lateral offset from the camera lane-line struct (perception, RF-valid). Line sub-message
     *  c0 (distance-to-marking) field number varies — returns 0 until calibrated on car (raw logged above). */
    private static double parseLaneLateral(byte[] s) {
        // framework: LaneLineDataNotifyStruct f3 = repeated Line; ego lateral = (leftLine.c0 + rightLine.c0)/2.
        // Without the confirmed Line.c0 field tag this stays 0 (sLaneApply off); calibrate from the LANE raw log.
        return 0.0;
    }

    // ---- BYDAuto HAL (speed/gear) via reflection — sanity + DR gating ----
    private static void startHal() {
        try {
            Object speed = hal("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Object gear  = hal("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            HudLog.f("HAL speed=" + (speed != null) + " gear=" + (gear != null));
            // (values polled on demand; listeners can be added later)
        } catch (Throwable t) { HudLog.f("HAL init fail: " + t); }
    }
    private static Object hal(String cls) {
        try { return Class.forName(cls).getMethod("getInstance", Context.class).invoke(null, sCtx); }
        catch (Throwable t) { return null; }
    }

    // ---- minimal protobuf reader (extract one field by number) ----
    /** If buf looks like a single embedded message at field 1 (tag 0x0A), return its bytes; else buf. */
    private static byte[] unwrap(byte[] buf) {
        try {
            if (buf.length > 2 && (buf[0] & 0xff) == 0x0A) {
                int[] p = {1}; int len = (int) readVarint(buf, p);
                if (p[0] + len <= buf.length) { byte[] inner = new byte[len]; System.arraycopy(buf, p[0], inner, 0, len); return inner; }
            }
        } catch (Throwable t) {}
        return buf;
    }
    /** read a double (wiretype 1) field by number, 0 if absent. */
    private static double pd(byte[] b, int field) {
        long bits = scan(b, field, 1);
        return bits == Long.MIN_VALUE ? 0 : Double.longBitsToDouble(bits);
    }
    /** read a varint (wiretype 0) field by number, 0 if absent. */
    private static long pv(byte[] b, int field) {
        long v = scan(b, field, 0);
        return v == Long.MIN_VALUE ? 0 : v;
    }
    /** scan protobuf for (field, wiretype); return raw value (varint) / 8-byte bits (fixed64), or MIN if absent. */
    private static long scan(byte[] b, int field, int wantWt) {
        int[] p = {0};
        try {
            while (p[0] < b.length) {
                long tag = readVarint(b, p); int f = (int) (tag >>> 3), wt = (int) (tag & 7);
                if (f == field && wt == wantWt) {
                    if (wt == 0) return readVarint(b, p);
                    if (wt == 1) { long v = le64(b, p[0]); return v; }
                }
                // skip
                switch (wt) {
                    case 0: readVarint(b, p); break;
                    case 1: p[0] += 8; break;
                    case 5: p[0] += 4; break;
                    case 2: { int l = (int) readVarint(b, p); p[0] += l; break; }
                    default: return Long.MIN_VALUE;
                }
            }
        } catch (Throwable t) {}
        return Long.MIN_VALUE;
    }
    private static long readVarint(byte[] b, int[] p) {
        long v = 0; int shift = 0;
        while (p[0] < b.length) { int x = b[p[0]++] & 0xff; v |= (long) (x & 0x7f) << shift; if ((x & 0x80) == 0) break; shift += 7; }
        return v;
    }
    private static long le64(byte[] b, int o) {
        long v = 0; for (int i = 0; i < 8; i++) v |= (long) (b[o + i] & 0xff) << (8 * i); return v;
    }
}
