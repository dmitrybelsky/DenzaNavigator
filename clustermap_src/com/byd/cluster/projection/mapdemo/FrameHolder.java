package com.byd.cluster.projection.mapdemo;

import android.graphics.Bitmap;

/** Latest Yandex map frame (set by ClusterMapService socket reader, read by the cluster activity). */
public final class FrameHolder {
    private static volatile Bitmap sLatest;
    private static volatile long sSeq;
    public static void set(Bitmap b) { sLatest = b; sSeq++; }
    public static Bitmap get() { return sLatest; }
    public static long seq() { return sSeq; }
}
