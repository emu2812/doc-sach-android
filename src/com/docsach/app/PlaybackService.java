package com.docsach.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * The app's native playback core.
 *
 * Keeps the process at foreground priority while the user listens (so
 * the WebView voice is never killed with the screen off), and gives the
 * book a real MediaSession: the book title and chapter appear on the
 * lock screen and in the media notification, with working controls —
 * previous/next chapter, back 15 s, forward 30 s, play/pause — plus
 * headset buttons and call handling (an incoming call pauses the book;
 * when the call ends, it resumes).
 *
 * Playback state comes from the page itself via NativePlayer; every
 * control here is routed back to the page's own buttons, so the page
 * stays the single source of truth.
 */
public class PlaybackService extends Service {

    private static final String CHANNEL_ID = "docsach_playback";
    private static final int NOTIFICATION_ID = 41;

    private PowerManager.WakeLock wakeLock;
    private MediaSession session;
    private AudioManager audioManager;
    private boolean focusHeld = false;
    private boolean resumeOnGain = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastNotifySignature = null;

    /** A command the USER issued (lock screen, notification, headset).
     *  It cancels any pending auto-resume: if the user pauses the book
     *  during a phone call, the book must stay paused when the call
     *  ends, and if they press play, it must not "resume" twice. */
    private void sendUser(String command) {
        if ("play".equals(command) || "pause".equals(command)) {
            resumeOnGain = false;
        }
        NativePlayer.send(command);
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        resumeOnGain = false;
                        if (NativePlayer.playing) {
                            NativePlayer.send("pause");
                        }
                        focusHeld = false;
                    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        // A call, or a prompt we cannot duck under (the
                        // page's volume is not ours to lower): pause and
                        // remember to resume when focus returns.
                        if (NativePlayer.playing) {
                            resumeOnGain = true;
                            NativePlayer.send("pause");
                        }
                        focusHeld = false;
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                        focusHeld = true;
                        if (resumeOnGain) {
                            resumeOnGain = false;
                            NativePlayer.send("play");
                        }
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Nghe sách chạy nền", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Điều khiển nghe sách và báo ứng dụng đang chạy nền");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        session = new MediaSession(this, "DocSach");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        // Media buttons come ONLY through this callback (the framework
        // routes them to the active session). There is deliberately no
        // separate broadcast receiver: two delivery paths would fire
        // every press twice.
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                sendUser("play");
            }

            @Override
            public void onPause() {
                sendUser("pause");
            }

            @Override
            public void onSkipToNext() {
                sendUser("next");
            }

            @Override
            public void onSkipToPrevious() {
                sendUser("prev");
            }

            @Override
            public void onFastForward() {
                sendUser("ff");
            }

            @Override
            public void onRewind() {
                sendUser("rew");
            }

            @Override
            public void onStop() {
                sendUser("pause");
            }
        }, mainHandler);
        // The session is activated lazily in refresh(), the first time
        // the page reports a book — activating it at service creation
        // would hijack headset buttons from the user's music app
        // before anything is playing here.

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "docsach:playback");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(12L * 60L * 60L * 1000L);
        }

        NativePlayer.setListener(new NativePlayer.Listener() {
            @Override
            public void onStateChanged() {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                });
            }
        });

        // The 3-arg startForeground (with the FGS type) exists only on
        // API 29+; older devices use the 2-arg call.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        refresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(12L * 60L * 60L * 1000L);
        }
        if (intent != null) {
            String cmd = intent.getStringExtra("cmd");
            if (cmd != null) {
                sendUser(cmd);
            }
        }
        // NOT_STICKY: this keeper is meaningless without a live
        // activity/WebView, so after a process death it must not
        // resurrect as an empty shell holding a wake lock.
        return START_NOT_STICKY;
    }

    /** Mirrors the page's reported state into the MediaSession, the
     *  notification and audio focus. Runs on the main thread. */
    private void refresh() {
        if (session == null) {
            return;
        }
        MediaMetadata.Builder md = new MediaMetadata.Builder();
        if (!NativePlayer.title.isEmpty()) {
            md.putString(MediaMetadata.METADATA_KEY_TITLE, NativePlayer.title);
            md.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, NativePlayer.title);
        }
        if (!NativePlayer.chapter.isEmpty()) {
            md.putString(MediaMetadata.METADATA_KEY_ARTIST, NativePlayer.chapter);
            md.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, NativePlayer.chapter);
        }
        session.setMetadata(md.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(NativePlayer.playing
                                ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        // The page tracks sentences, not milliseconds;
                        // publishing a sentence ordinal as a position
                        // would be a lie to the system UI.
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, NativePlayer.rate)
                .build());

        if (!session.isActive()
                && (NativePlayer.playing || !NativePlayer.title.isEmpty())) {
            session.setActive(true);
        }

        // YouTube mode is exempt from the focus request: the video
        // player inside the WebView holds audio focus itself, and
        // this request used to steal it right back — the system
        // granted the service focus, the WebView player got a focus
        // LOSS and paused, the page reported paused, the service
        // abandoned focus, and the next play repeated the cycle:
        // the video played a few seconds and stopped, every time.
        if (NativePlayer.playing && !focusHeld && audioManager != null
                && !"yt".equals(NativePlayer.mode)) {
            int result = audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focusHeld) {
                // Another app owns the audio (e.g. a call is up): do
                // not blast over it — pause the book instead.
                NativePlayer.send("pause");
            }
        } else if (!NativePlayer.playing && focusHeld && audioManager != null) {
            audioManager.abandonAudioFocus(focusListener);
            focusHeld = false;
        }

        // Rebuild the notification only when what it SHOWS changes —
        // the page reports every sentence, and re-posting on each one
        // flickers the shade and can hit notification throttling.
        String signature = NativePlayer.title + "|" + NativePlayer.chapter
                + "|" + NativePlayer.playing + "|" + NativePlayer.pos
                + "/" + NativePlayer.count;
        if (!signature.equals(lastNotifySignature)) {
            lastNotifySignature = signature;
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        }
    }

    private PendingIntent commandIntent(String cmd) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.putExtra("cmd", cmd);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        // Stable request codes (String.hashCode works but is fragile).
        int code = 10;
        if ("prev".equals(cmd)) {
            code = 11;
        } else if ("rew".equals(cmd)) {
            code = 12;
        } else if ("play".equals(cmd)) {
            code = 13;
        } else if ("pause".equals(cmd)) {
            code = 14;
        } else if ("ff".equals(cmd)) {
            code = 15;
        } else if ("next".equals(cmd)) {
            code = 16;
        }
        return PendingIntent.getService(this, code, intent, flags);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, piFlags);

        // The channel-id Builder constructor only exists on API 26+.
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_speaker)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (NativePlayer.title.isEmpty()) {
            builder.setContentTitle("Đọc Sách Bằng Giọng Nói")
                    .setContentText("Đang chạy nền — nghe sách tiếp khi tắt màn hình");
        } else {
            String chapterLine = NativePlayer.chapter;
            if (NativePlayer.count > 0) {
                String progress = "Câu " + (NativePlayer.pos + 1) + "/" + NativePlayer.count;
                chapterLine = chapterLine.isEmpty() ? progress : chapterLine + " • " + progress;
            }
            builder.setContentTitle(NativePlayer.title)
                    .setContentText(chapterLine)
                    .setStyle(new Notification.MediaStyle()
                            .setMediaSession(session != null ? session.getSessionToken() : null)
                            .setShowActionsInCompactView(1, 2, 3))
                    .addAction(android.R.drawable.ic_media_previous, "Chương trước",
                            commandIntent("prev"))
                    .addAction(android.R.drawable.ic_media_rew, "Lùi 15 giây",
                            commandIntent("rew"))
                    .addAction(NativePlayer.playing
                                    ? android.R.drawable.ic_media_pause
                                    : android.R.drawable.ic_media_play,
                            NativePlayer.playing ? "Tạm dừng" : "Phát tiếp",
                            commandIntent(NativePlayer.playing ? "pause" : "play"))
                    .addAction(android.R.drawable.ic_media_ff, "Tới 30 giây",
                            commandIntent("ff"))
                    .addAction(android.R.drawable.ic_media_next, "Chương sau",
                            commandIntent("next"));
        }
        return builder.build();
    }

    @Override
    public void onDestroy() {
        NativePlayer.setListener(null);
        if (audioManager != null && focusHeld) {
            audioManager.abandonAudioFocus(focusListener);
            focusHeld = false;
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
