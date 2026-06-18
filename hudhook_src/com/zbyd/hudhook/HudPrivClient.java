package com.zbyd.hudhook;

/**
 * Thin facade over {@link HudBridge} for privileged cluster/HUD/body commands. The agent (HudPrivAgent
 * in com.byd.cameraautostudy) executes them with its signature-permitted UID. Transport is the /sdcard
 * directory queue — see HudBridge.
 */
public final class HudPrivClient {

    private static void send(String line) { HudBridge.send(line); }

    public static void cluster(int turnType, int distM, String road) {
        String r = (road == null || road.isEmpty()) ? "" : " " + road.replace('\n', ' ').replace('\r', ' ');
        send("CLUSTER " + turnType + " " + distM + r);
    }

    public static void camera(int camType, int distM, int roadClass) {
        send("CAMERA " + camType + " " + distM + " " + roadClass);
    }

    public static void status(int n) { send("STATUS " + n); }

    /** Invoke a BYDAutoSettingDevice setter (HUD layout / nav-map / seat heat-vent) by method name + int args. */
    public static void setting(String method, int... vals) {
        StringBuilder sb = new StringBuilder("SETTING ").append(method);
        for (int v : vals) sb.append(' ').append(v);
        send(sb.toString());
    }

    /** Invoke a BYDAutoBodyworkDevice setter (windows/doors/moonroof/trunk) by method name + int args. */
    public static void body(String method, int... vals) {
        StringBuilder sb = new StringBuilder("BODY ").append(method);
        for (int v : vals) sb.append(' ').append(v);
        send(sb.toString());
    }

    // Seat comfort (BYDAutoSettingDevice.setSeat{Heating,Ventilating}State(seat, level); seat 1..n, level OFF=1/LOW=2/HIGH=3).
    public static void seatHeat(int seat, int level) { setting("setSeatHeatingState", seat, level); }
    public static void seatVent(int seat, int level) { setting("setSeatVentilatingState", seat, level); }
    // Steering-wheel heat (setSteeringWheelHeatingState: OFF=1, ON=2).
    public static void wheelHeat(boolean on) { setting("setSteeringWheelHeatingState", on ? 2 : 1); }

    /** Write a raw BYD instrument feature-id (hex "0x..") — cluster nav fields (ETA/mileage/trip/safety). */
    public static void fidSet(String hexFid, int val) { send("FIDSET " + hexFid + " " + val); }

    /** Write a raw BYD bodywork feature-id (dev 1001) — e.g. rear sunshade (no high-level wrapper exists). */
    public static void bodyFid(int fid, int val) { send("BODYFID " + fid + " " + val); }

    // --- Panorama sunshades (N9 roof = fixed glass + electric shades; config 8 = front+rear sunshade) ---
    // Front shade: setSunshadeState(pct) 0=closed/100=open (validated live on N9). Rear: raw fid (no method).
    private static final int REAR_SUNSHADE_PERCENT_FID = 1276178472;   // BODYWORK_REAR_SUNSHADE_PANEL_PERCENT_SET (CanFD)
    public static void sunshade(int pct)     { body("setSunshadeState", pct); }
    public static void rearSunshade(int pct) { bodyFid(REAR_SUNSHADE_PERCENT_FID, pct); }
}
