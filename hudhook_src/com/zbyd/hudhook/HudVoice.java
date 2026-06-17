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
        if (has(s, "подогрев сид") || has(s, "обогрев сид")) { HudPrivClient.seatHeat(1, on ? 3 : 0); ok(on ? "Включаю подогрев сиденья" : "Выключаю подогрев"); return true; }
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
