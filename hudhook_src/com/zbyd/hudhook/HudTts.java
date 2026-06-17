package com.zbyd.hudhook;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * Speak Yandex maneuvers through the CAR's own TTS engine (iFlytek on BYD) via the standard android
 * TextToSpeech API — in-process, non-root, no reach into com.byd.autovoice. Synthesises a short Russian
 * prompt from the extracted icon/distance/road. OFF by default (sEnabled) to avoid double-speak with
 * Yandex's own voice; flip on car if the native voice is preferred. Dedupes per maneuver.
 */
public final class HudTts {

    private static volatile boolean sEnabled = false;     // opt-in (avoid doubling Yandex voice)
    private static volatile TextToSpeech sTts;
    private static volatile boolean sReady;
    private static int sLastIcon = -99, sLastBand = -1;

    public static void setEnabled(boolean e) { sEnabled = e; }

    private static void ensure(Context c) {
        if (sTts != null || c == null) return;
        try {
            final Context app = c.getApplicationContext();
            sTts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    sReady = (status == TextToSpeech.SUCCESS);
                    if (sReady) {
                        try { sTts.setLanguage(new Locale("ru")); } catch (Throwable t) {}
                        try { sTts.setAudioAttributes(HudAudio.navAttrs()); } catch (Throwable t) {}  // duck music + guidance stream
                        try { sTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                            @Override public void onStart(String id) { HudAudio.duck(app); }
                            @Override public void onDone(String id) { HudAudio.release(); }
                            @Override public void onError(String id) { HudAudio.release(); }
                        }); } catch (Throwable t) {}
                    }
                    HudLog.f("HudTts init ready=" + sReady + " engine=" + (sTts == null ? "?" : sTts.getDefaultEngine()));
                }
            });
        } catch (Throwable t) { HudLog.f("HudTts init fail: " + t); }
    }

    /** Speak an arbitrary phrase (EV range summary, alerts) on the nav-guidance audio channel. */
    public static void say(Context c, String text) {
        if (!sEnabled || text == null) return;
        ensure(c);
        if (!sReady) return;
        try { sTts.speak(text, TextToSpeech.QUEUE_ADD, null, "zbydsay"); } catch (Throwable t) {}
    }

    /** Called on each maneuver. Speaks once per (icon, distance-band) to avoid spamming. */
    public static void maneuver(Context c, int bydIcon, int distM, String road) {
        if (!sEnabled) return;
        ensure(c);
        if (!sReady) return;
        int band = distM > 600 ? 3 : distM > 250 ? 2 : distM > 80 ? 1 : 0;   // announce per distance band
        if (bydIcon == sLastIcon && band == sLastBand) return;
        sLastIcon = bydIcon; sLastBand = band;
        try {
            String s = phrase(bydIcon, distM, road);
            if (s != null) sTts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "zbydhud");
        } catch (Throwable t) {}
    }

    private static String phrase(int icon, int dist, String road) {
        String turn;
        switch (icon) {
            case 2: case 4: case 6: case 17: turn = "поверните налево"; break;
            case 3: case 5: case 7: case 18: turn = "поверните направо"; break;
            case 8:  turn = "развернитесь"; break;
            case 11: turn = "въезд на кольцо"; break;
            case 15: return "Прибытие в пункт назначения";
            case 9:  default: turn = "продолжайте движение"; break;
        }
        String where = (road != null && road.length() > 0) ? " на " + road : "";
        if (dist >= 100) return "Через " + (dist / 100 * 100) + " метров " + turn + where;
        return turn + where;
    }
}
