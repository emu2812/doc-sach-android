package com.docsach.app;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * On-device text-to-speech: the unlimited fallback voice tier.
 *
 * When the premium AI voice is out of quota, the page asks this
 * engine to read instead. It uses the phone's own TTS engine —
 * Google's Speech Services (com.google.android.tts) when installed,
 * otherwise the system default — so it costs nothing, has no quota,
 * and works offline. Quality is whatever voice the phone carries;
 * for Vietnamese that is Google's vi-VN voice on nearly every
 * Android phone sold in Vietnam.
 *
 * Events flow back to the page through the Sink as JSON strings:
 * {type:"ready", ok, engine} / {type:"start"|"done", uid} /
 * {type:"error", uid?, reason}.
 */
final class NativeTts {

    interface Sink {
        void onEvent(String json);
    }

    private static final String GOOGLE_ENGINE = "com.google.android.tts";

    private final Context appContext;
    private final Sink sink;
    private TextToSpeech tts;
    private boolean ready;
    private boolean triedGoogle;
    private String lastLang = "";

    NativeTts(Context context, Sink sink) {
        this.appContext = context.getApplicationContext();
        this.sink = sink;
    }

    /** Idempotent: the page calls this on load. Tries Google's
     *  engine first, then falls back to the system default engine. */
    synchronized void init() {
        if (tts != null || triedGoogle) {
            return;
        }
        triedGoogle = true;
        startEngine(GOOGLE_ENGINE);
    }

    private void startEngine(final String engine) {
        TextToSpeech created;
        try {
            TextToSpeech.OnInitListener listener = new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        synchronized (NativeTts.this) {
                            ready = true;
                        }
                        emit("{\"type\":\"ready\",\"ok\":true,\"engine\":"
                                + JSONObject.quote(engine == null ? "default" : engine) + "}");
                    } else if (engine != null) {
                        // Google's engine is not usable here: retry
                        // once with whatever the system defaults to.
                        startEngine(null);
                    } else {
                        emit("{\"type\":\"ready\",\"ok\":false}");
                    }
                }
            };
            created = engine == null
                    ? new TextToSpeech(appContext, listener)
                    : new TextToSpeech(appContext, listener, engine);
        } catch (Exception e) {
            if (engine != null) {
                startEngine(null);
            } else {
                emit("{\"type\":\"ready\",\"ok\":false}");
            }
            return;
        }
        synchronized (this) {
            tts = created;
        }
        created.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                emit(event("start", utteranceId));
            }

            @Override
            public void onDone(String utteranceId) {
                emit(event("done", utteranceId));
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                emit(errorEvent(utteranceId, "speak"));
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                emit(errorEvent(utteranceId, "speak" + errorCode));
            }
        });
    }

    /**
     * Speaks a queue of utterances. Payload JSON:
     * {rate: 1.0, lang: "vi", items: [{uid: "n0", text: "..."}, ...]}
     * The first item flushes whatever was queued before.
     */
    synchronized void speak(String json) {
        if (tts == null || !ready) {
            emit("{\"type\":\"error\",\"reason\":\"not_ready\"}");
            return;
        }
        try {
            JSONObject payload = new JSONObject(json);
            String lang = payload.optString("lang", "vi");
            if (!lang.equals(lastLang)) {
                Locale locale = localeFor(lang);
                int res = tts.setLanguage(locale);
                if (res == TextToSpeech.LANG_MISSING_DATA
                        || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    emit("{\"type\":\"error\",\"reason\":\"lang\"}");
                    return;
                }
                lastLang = lang;
            }
            double rate = payload.optDouble("rate", 1.0);
            if (rate < 0.5) rate = 0.5;
            if (rate > 2.0) rate = 2.0;
            tts.setSpeechRate((float) rate);
            JSONArray items = payload.optJSONArray("items");
            if (items == null || items.length() == 0) {
                emit("{\"type\":\"error\",\"reason\":\"empty\"}");
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String text = item.optString("text", "");
                String uid = item.optString("uid", "n" + i);
                if (text.length() == 0) continue;
                int mode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                tts.speak(text, mode, null, uid);
                // a narrator's micro-breath between sentences: queued
                // back-to-back, the engine reads like a telegram; a
                // short silence makes the device voice flow. The page
                // ignores these uids (they map to no sentence).
                if (i < items.length() - 1) {
                    tts.playSilentUtterance(160, TextToSpeech.QUEUE_ADD, "sil" + i);
                }
            }
        } catch (Exception e) {
            emit("{\"type\":\"error\",\"reason\":\"bad_payload\"}");
        }
    }

    synchronized void stop() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    synchronized void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
            ready = false;
        }
    }

    private static Locale localeFor(String lang) {
        if (lang == null || lang.length() == 0) return new Locale("vi");
        String code = lang.toLowerCase(Locale.US);
        if (code.startsWith("vi")) return new Locale("vi");
        if (code.startsWith("en")) return Locale.US;
        if (code.startsWith("fr")) return Locale.FRENCH;
        if (code.startsWith("ja")) return Locale.JAPANESE;
        if (code.startsWith("ko")) return Locale.KOREAN;
        if (code.startsWith("zh")) return Locale.SIMPLIFIED_CHINESE;
        if (code.startsWith("de")) return Locale.GERMAN;
        if (code.startsWith("ru")) return new Locale("ru");
        if (code.startsWith("es")) return new Locale("es");
        return new Locale(code.substring(0, Math.min(2, code.length())));
    }

    private static String event(String type, String uid) {
        return "{\"type\":\"" + type + "\",\"uid\":" + JSONObject.quote(uid == null ? "" : uid) + "}";
    }

    private static String errorEvent(String uid, String reason) {
        return "{\"type\":\"error\",\"uid\":" + JSONObject.quote(uid == null ? "" : uid)
                + ",\"reason\":" + JSONObject.quote(reason) + "}";
    }

    private void emit(String json) {
        try {
            sink.onEvent(json);
        } catch (Exception ignored) {
        }
    }
}
