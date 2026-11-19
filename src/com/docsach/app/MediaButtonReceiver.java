package com.docsach.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * Headset / Bluetooth media buttons. The MediaSession in
 * PlaybackService points its media-button receiver here; each key is
 * translated into the same commands the lock-screen controls send, so
 * every control surface behaves identically.
 */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                NativePlayer.send("play");
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                NativePlayer.send("pause");
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                NativePlayer.send(NativePlayer.playing ? "pause" : "play");
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                NativePlayer.send("next");
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                NativePlayer.send("prev");
                break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                NativePlayer.send("ff");
                break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                NativePlayer.send("rew");
                break;
            default:
                break;
        }
    }
}
