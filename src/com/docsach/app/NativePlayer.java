package com.docsach.app;

import org.json.JSONObject;

/**
 * Shared playback state between the web page (source of truth, reports
 * through the JS bridge in MainActivity), the foreground PlaybackService
 * (MediaSession + notification + audio focus) and media-button events.
 * This class only mirrors the page's state and routes commands back to
 * it; it never invents playback state of its own.
 */
public final class NativePlayer {

    public interface Listener {
        void onStateChanged();
    }

    public interface CommandSink {
        void send(String command);
    }

    public static volatile boolean playing = false;
    public static volatile String title = "";
    public static volatile String chapter = "";
    public static volatile int chapterIdx = 0;
    public static volatile int chapterCount = 0;
    public static volatile int pos = 0;
    public static volatile int count = 0;
    public static volatile float rate = 1f;

    private static volatile Listener listener;
    private static volatile CommandSink sink;

    private NativePlayer() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void setSink(CommandSink s) {
        sink = s;
    }

    public static void updateFromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            playing = o.optBoolean("playing", false);
            title = o.optString("title", "");
            chapter = o.optString("chapter", "");
            chapterIdx = o.optInt("chapterIdx", 0);
            chapterCount = o.optInt("chapterCount", 0);
            pos = o.optInt("pos", 0);
            count = o.optInt("count", 0);
            rate = (float) o.optDouble("rate", 1.0);
        } catch (Exception e) {
            return;
        }
        Listener l = listener;
        if (l != null) {
            l.onStateChanged();
        }
    }

    /** Commands: play, pause, next, prev, ff, rew. */
    public static void send(String command) {
        CommandSink s = sink;
        if (s != null) {
            s.send(command);
        }
    }
}
