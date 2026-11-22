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
    /** Page playback mode: "tts", "audio" or "yt". The service
     *  must not grab audio focus away from YouTube playback (see
     *  PlaybackService), so the mode travels with the state. */
    public static volatile String mode = "";
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

    /** Called on a JavaBridge thread. Values are clamped: the bridge
     *  is reachable by whatever page the WebView shows, so nothing it
     *  sends may push an out-of-range value into a system API. The
     *  update is synchronized so readers never see a torn snapshot
     *  (new title with the previous book's chapter). */
    public static synchronized void updateFromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            playing = o.optBoolean("playing", false);
            mode = o.optString("mode", "");
            title = o.optString("title", "");
            chapter = o.optString("chapter", "");
            chapterIdx = Math.max(0, o.optInt("chapterIdx", 0));
            chapterCount = Math.max(0, o.optInt("chapterCount", 0));
            pos = Math.max(0, o.optInt("pos", 0));
            count = Math.max(0, o.optInt("count", 0));
            double r = o.optDouble("rate", 1.0);
            if (Double.isNaN(r) || r < 0.25) {
                r = 0.25;
            } else if (r > 4.0) {
                r = 4.0;
            }
            rate = (float) r;
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
