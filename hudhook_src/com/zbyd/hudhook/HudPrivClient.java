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

    // --- Side electric doors (N9 auto doors). Bodywork CTRL fids (agent holds BODYWORK_SET). ---
    // op = CarElectricDoorOperate (verify live: likely 1=open / 2=close / 3=stop). Position via *_OPEN_ANGLE_SET.
    public static final int DOOR_LF = 1276178480, DOOR_LR = 1276178488, DOOR_RF = 1276178483, DOOR_RR = 1276178491;
    public static final int DOOR_LF_ANGLE = 0x4C117010, DOOR_LR_ANGLE = 0x4C117020,
                            DOOR_RF_ANGLE = 0x4C117018, DOOR_RR_ANGLE = 0x4C117028;
    public static void door(int doorFid, int op)        { bodyFid(doorFid, op); }      // open/close one side door
    public static void doorAngle(int angleFid, int pct) { bodyFid(angleFid, pct); }    // open-angle 0..100

    // --- Rear-window sunshade (rear windshield / RSE shade). Bodywork fids (BODYWORK_SET). ---
    public static final int REAR_WINDOW_SHADE_CTRL = 1276207170;       // BODYWORK_REAR_SUNSHADE_CONTROL_SET
    public static final int RSE_REAR_SHADE_PCT     = 412151840;        // BODYWORK_RSE_REAR_SUNSHADE_PANEL_PERCENT_SET
    public static void rearWindowShade(int v)  { bodyFid(REAR_WINDOW_SHADE_CTRL, v); } // open/close (tune value live)
    public static void rseRearShade(int pct)   { bodyFid(RSE_REAR_SHADE_PCT, pct); }   // percent 0..100

    // --- SETTING "scene request command" channel (0x32B0A0xx, SETTING domain). The agent holds SETTING_SET,
    //     so these reach AC/light/lock/window WITHOUT the per-device AC_SET/LIGHT_SET/DOOR_LOCK_SET perms and
    //     WITHOUT scenemodes. Values are scene-request command codes (from NAP: light=2/lock=1/window=2) —
    //     verify on/off semantics live. Written via DSET on BYDAutoSettingDevice. ---
    private static final String CLS_SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    public static void settingFid(int fid, int val) { send("DSET " + CLS_SETTING + " " + fid + " " + val); }
    public static final int REQ_AC = 850436149, REQ_INTERIOR_LIGHT = 850436128, REQ_ATMOSPHERE = 850436131,
                            REQ_DOORLOCK = 850436141, REQ_WINDOW = 850436120, REQ_SEAT = 850436144;
    public static final int REQ_REAR_PANEL = 850436156;   // SETTING_REAR_PANEL_STATUS_REQUEST_COMMAND_SET (0x32B0A03C)
    // OHS / rear overhead-screen panel via the SETTING request-command channel (agent SETTING_SET) — bypasses
    // the MOTOR_SET wall. A "leave car" scene sets this=1 (retract). Find the deploy/open value live.
    public static void rearPanel(int v)        { settingFid(REQ_REAR_PANEL, v); }
    public static void acReq(int v)            { settingFid(REQ_AC, v); }            // climate scene-request
    public static void interiorLightReq(int v) { settingFid(REQ_INTERIOR_LIGHT, v); }// cabin light (2=on per NAP)
    public static void ambientReq(int v)       { settingFid(REQ_ATMOSPHERE, v); }    // ambient (2=on per NAP)
    public static void doorLockReq(int v)      { settingFid(REQ_DOORLOCK, v); }      // 1=lock per NAP

    // Seat massage (BYDAutoSettingDevice high-level; SETTING_SET). seat index 1..n, level/mode 0=off.
    public static void massageLevel(int seat, int level) { setting("setMassageLevel", seat, level); }
    public static void massageMode(int seat, int mode)   { setting("setMassageMode", seat, mode); }

    // Fridge (the icebox app actuates these directly; bodywork = work-state/door, setting = temp/type).
    public static final int FRIDGE_WORK = 1276182546, REAR_FRIDGE_WORK = 850550800, REAR_FRIDGE_DOOR = 850550824;
    public static void fridge(boolean on)        { bodyFid(FRIDGE_WORK, on ? 1 : 0); }        // front fridge on/off
    public static void rearFridge(boolean on)    { bodyFid(REAR_FRIDGE_WORK, on ? 1 : 0); }
    public static void rearFridgeDoor(int op)    { bodyFid(REAR_FRIDGE_DOOR, op); }            // door open/close (tune live)

    // NOTE: rear overhead screen (OHS) = MOTOR domain (MOTOR_OVERHEAD_SCREEN_* / MOTOR_IVI_TO_REAR_LARGE_SCREEN
    // = 1285554288). cameraautostudy lacks MOTOR_SET -> NOT drivable by this agent. Use com.byd.scenemodes (MOTOR_SET).
}
