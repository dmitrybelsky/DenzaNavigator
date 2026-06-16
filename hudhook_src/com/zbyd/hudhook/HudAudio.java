package com.zbyd.hudhook;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

/**
 * Media ducking for nav prompts. Uses the standard Android audio-focus contract: request
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> the car's media
 * center (and any media app) automatically lowers music while our prompt plays, then restores. No
 * BYD-specific reversing needed (com.byd.mediacenter MediaControl.ACTION_NAVIGATION is just how the
 * media center internally reacts to exactly this focus loss). Used by HudTts around speak().
 */
public final class HudAudio {

    private static AudioManager sAm;
    private static AudioFocusRequest sReq;
    private static volatile boolean sHeld;

    /** Nav-guidance attributes — ducks media + routes to the guidance stream. */
    public static AudioAttributes navAttrs() {
        return new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    }

    public static synchronized void duck(Context c) {
        if (sHeld || c == null) return;
        try {
            if (sAm == null) sAm = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            sReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navAttrs())
                .setWillPauseWhenDucked(false)
                .build();
            int r = sAm.requestAudioFocus(sReq);
            sHeld = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } catch (Throwable t) {}
    }

    public static synchronized void release() {
        if (!sHeld) return;
        try { if (sAm != null && sReq != null) sAm.abandonAudioFocusRequest(sReq); } catch (Throwable t) {}
        sHeld = false;
    }
}
