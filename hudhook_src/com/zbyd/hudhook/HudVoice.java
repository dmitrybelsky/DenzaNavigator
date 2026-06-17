package com.zbyd.hudhook;

import android.content.Context;

/**
 * Voice car-control via Alice interception. The smali hook on the SpeechKit RecognizerListener
 * (com.yandex.alice.voice.*, located by `.implements ru/yandex/speechkit/RecognizerListener`) feeds
 * us the final recognized phrase; we parse a small Russian command grammar and actuate the car through
 * the shell-uid autoservice helper (HudCarClient) / privileged agent (HudPrivClient). Alice keeps its
 * own (nav/chat) handling — these phrases it would just not understand. No cloud, no extra mic.
 *
 * Grammar (substring match): открой/закрой/подними/опусти + окно|стекло|дверь|замок|багажник|люк ;
 * включи/выключи + климат|кондиционер|свет|подсветку|обогрев ; температура <N> ; подогрев сиденья.
 */
public final class HudVoice {

    private static Context sCtx;
    private static long sLast;

    public static void attach(Context c) { sCtx = c == null ? sCtx : c.getApplicationContext(); }

    /** Called by the recognizer hook with each result; act only on the final utterance. */
    public static void onRecognized(String text, boolean isFinal) {
        if (!isFinal || text == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLast < 1500) return;                          // debounce repeats
        try { if (handle(text.toLowerCase(java.util.Locale.ROOT))) sLast = now; } catch (Throwable t) {}
    }

    private static boolean handle(String s) {
        if (has(s, "давлен", "шин", "колес")) {                  // query: speak tire pressures
            int[] t = HudCarClient.tpms();
            ok("Давление в шинах: " + t[0] + ", " + t[1] + ", " + t[2] + ", " + t[3]);
            return true;
        }
        if (has(s, "заряд", "запас хода", "сколько проеду")) {   // query: speak SOC/range
            ok("Заряд " + HudCarClient.soc() + " процентов, запас хода " + (int) HudCarClient.rangeKm() + " километров");
            return true;
        }
        boolean tOn = has(s, "включи", "вкл"), tOff = has(s, "выключи", "выкл", "отключи");
        if ((tOn || tOff) && has(s, "автозапуск", "преднагрев", "прогрев")) {
            HudFlags.set(sCtx, HudFlags.AUTOSTART, tOn); ok(tOn ? "Автозапуск включён" : "Автозапуск выключен"); return true;
        }
        if ((tOn || tOff) && has(s, "автоматик")) {
            HudFlags.set(sCtx, HudFlags.MASTER, tOn); ok(tOn ? "Автоматика включена" : "Автоматика выключена"); return true;
        }
        if (has(s, "прогрей", "подготовь", "запусти машин", "разогрей")) {  // on-demand preconditioning
            HudCarClient.ac(true); ok("Прогреваю машину"); return true;
        }
        if ((tOn || tOff) && has(s, "подогрев руля", "обогрев руля", "руль")) {
            if (has(s, "старт", "автозапуск")) { HudFlags.set(sCtx, HudFlags.WHEEL_HEAT, tOn); ok(tOn ? "Подогрев руля на старте включён" : "Подогрев руля на старте выключен"); }
            else { HudPrivClient.wheelHeat(tOn); ok(tOn ? "Включаю подогрев руля" : "Выключаю подогрев руля"); }
            return true;
        }
        if ((tOn || tOff) && has(s, "подогрев сид", "обогрев сид", "подогрев кресел", "подогрев сидений")) {
            if (has(s, "старт", "автозапуск")) { HudFlags.set(sCtx, HudFlags.SEAT_HEAT, tOn); ok(tOn ? "Подогрев сидений на старте включён" : "Подогрев сидений на старте выключен"); }
            else { HudPrivClient.seatHeat(1, tOn ? 3 : 1); HudPrivClient.seatHeat(2, tOn ? 3 : 1); ok(tOn ? "Включаю подогрев сидений" : "Выключаю подогрев сидений"); }
            return true;
        }
        boolean open  = has(s, "открой", "подними", "опусти", "включи", "разблокир");
        boolean close = has(s, "закрой", "выключи", "заблокир");
        if (!open && !close) {
            int t = temp(s);                                     // "температура 22"
            if (t > 0) { HudCarClient.acTemp(t); ok("Температура " + t); return true; }
            return false;
        }
        boolean on = open;                                       // open == on/up/unlock

        if (has(s, "окно", "стекл")) {
            boolean up = has(s, "подними", "закрой", "выключи"); // "опусти/открой окно" = down/open
            int v = up ? 0 : 100;
            if (has(s, "пассажир")) { HudCarClient.write(1001, 1276219424, v); ok(up ? "Закрываю окно пассажира" : "Открываю окно пассажира"); }
            else if (has(s, "все"))  { for (int f : new int[]{1276219408, 1276219416, 1276219424, 1276219432}) HudCarClient.write(1001, f, v); ok(up ? "Закрываю окна" : "Открываю окна"); }
            else { HudCarClient.windowDriver(!up); ok(up ? "Закрываю окно" : "Открываю окно"); }
            return true;
        }
        if (has(s, "багажник")) { HudPrivClient.body("setHetchDoorStatus", on ? 1 : 2); ok(on ? "Открываю багажник" : "Закрываю багажник"); return true; }
        if (has(s, "люк"))      { HudCarClient.sunroof(on ? 1 : 2); ok(on ? "Открываю люк" : "Закрываю люк"); return true; }
        if (has(s, "замок", "двер")) { HudCarClient.lockDoors(!on); ok(on ? "Разблокирую двери" : "Блокирую двери"); return true; }
        if (has(s, "климат", "кондиц")) { HudCarClient.ac(on); ok(on ? "Включаю климат" : "Выключаю климат"); return true; }
        if (has(s, "подсветк", "амбиент")) { HudCarClient.ambientLight(on); ok(on ? "Включаю подсветку" : "Выключаю подсветку"); return true; }
        if (has(s, "свет")) { HudCarClient.interiorLight(on); ok(on ? "Включаю свет" : "Выключаю свет"); return true; }
        return false;
    }

    private static int temp(String s) {
        if (!has(s, "температ", "градус")) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2})").matcher(s);
            if (m.find()) { int t = Integer.parseInt(m.group(1)); if (t >= 16 && t <= 30) return t; }
        } catch (Throwable t) {}
        return 0;
    }

    private static boolean has(String s, String... keys) { for (String k : keys) if (s.contains(k)) return true; return false; }

    private static void ok(String confirm) {
        HudLog.f("VOICE -> " + confirm);
        try { HudTts.say(sCtx, confirm); } catch (Throwable t) {}
    }
}
