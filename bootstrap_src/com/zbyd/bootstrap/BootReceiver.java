package com.zbyd.bootstrap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts BootstrapService on boot (or LOCKED_BOOT_COMPLETED / our manual action). */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try { c.startForegroundService(new Intent(c, BootstrapService.class)); } catch (Throwable t) {}
    }
}
