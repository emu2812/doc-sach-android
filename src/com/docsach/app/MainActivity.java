package com.docsach.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Thin WebView shell for the "Đọc Sách Bằng Giọng Nói" web app.
 *
 * The whole product (library, reading, voices, audiobooks) lives on the
 * server at HOME_URL; this activity only hosts it full-screen and adds
 * the three things a web page cannot do by itself on Android:
 *   1. Background playback — a foreground service (PlaybackService)
 *      keeps the process alive, and the WebView is deliberately never
 *      paused, so listening continues with the screen off.
 *   2. A real file picker for the page's "add book" upload input.
 *   3. Real downloads (exported audiobooks/text) via DownloadManager.
 * No native UI of its own beyond that.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL =
            "https://doc-sach.nguyenhailinh140101.workers.dev/";

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int SYNC_FOLDER_REQUEST = 4213;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private NativeTts nativeTts;

    /** Builds the WebView with all settings and clients. Also used to
     *  recover when the renderer process dies mid-session. */
    private WebView buildWebView() {
        WebView webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // The page chains short audio chunks; without this, WebView can
        // refuse to start the next chunk while the app is backgrounded.
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Required for onCreateWindow below to fire at all.
        settings.setSupportMultipleWindows(true);

        // Bridge to the native playback core: the page reports its
        // playback state (book, chapter, sentence, playing) and the
        // native layer (lock screen, notification, headset, calls)
        // sends commands back through the sink registered in onCreate.
        webView.addJavascriptInterface(new NativeBridge(), "DocSachBridge");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkCloudCopy();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                // Only the main page failing matters (a dead font or
                // script request must not replace the book): show a
                // friendly offline page with a retry button instead of
                // the browser's blank error screen.
                if (request != null && request.isForMainFrame()) {
                    showOfflinePage(view);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url != null ? url.getScheme() : null;
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false; // load inside the WebView
                }
                // A small allowlist of links a book may contain: hand
                // them to the phone's own apps instead of dead-ending.
                if ("tel".equals(scheme) || "mailto".equals(scheme) || "sms".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (Exception e) {
                        // No app can take it: swallow quietly.
                    }
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                                 android.webkit.RenderProcessGoneDetail detail) {
                // The Chromium renderer died (usually memory pressure
                // during a long session). Rebuild the WebView and reload
                // instead of letting the whole app crash; the page
                // restores the reading position itself.
                if (view == MainActivity.this.webView) {
                    WebView fresh = buildWebView();
                    MainActivity.this.webView = fresh;
                    setContentView(fresh);
                    try {
                        view.destroy();
                    } catch (Exception e) {
                        // Already gone; nothing to clean up.
                    }
                    fresh.loadUrl(HOME_URL);
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // A link that asks for a new window (target=_blank /
            // window.open) would otherwise die silently in this
            // single-window shell. Capture the URL through a throwaway
            // WebView and hand it to the system browser instead.
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                WebView probe = new WebView(MainActivity.this);
                probe.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception e) {
                            // no browser able to take it: leave the app as is
                        }
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    Toast.makeText(MainActivity.this,
                            "Tải xuống dạng này chỉ làm được trên trình duyệt web",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) {
                        request.addRequestHeader("Cookie", cookie);
                    }
                    request.addRequestHeader("User-Agent", userAgent);
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setTitle(fileName);
                    request.setMimeType(mimeType);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this,
                                "Đang tải xuống: " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Không tải xuống được", Toast.LENGTH_SHORT).show();
                }
            }
        });

        return webView;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = buildWebView();
        setContentView(webView);

        // Notification permission (Android 13+): the foreground service's
        // ongoing notification is what keeps the background playback
        // visible and trustworthy; without it some phones kill silently.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        // Start the background-playback keeper right away so the process
        // is protected before the user ever leaves the app.
        Intent service = new Intent(this, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        // Native → page commands. Lock-screen controls, the media
        // notification, headset buttons and call handling all land
        // here; each command clicks the page's own button, and the
        // play/pause pair is guarded by the page's reported state
        // because the page's play button is a toggle.
        NativePlayer.setSink(new NativePlayer.CommandSink() {
            @Override
            public void send(String command) {
                final WebView w = webView;
                if (w == null) {
                    return;
                }
                final String id;
                if ("play".equals(command)) {
                    if (NativePlayer.playing) {
                        return;
                    }
                    // Optimistic flip: the page's confirmation arrives
                    // on the next bridge report (up to 1.5 s later), and
                    // a second command inside that window must see the
                    // NEW state, not toggle back.
                    NativePlayer.playing = true;
                    id = "btnPlay";
                } else if ("pause".equals(command)) {
                    if (!NativePlayer.playing) {
                        return;
                    }
                    NativePlayer.playing = false;
                    id = "btnPlay";
                } else if ("next".equals(command)) {
                    id = "btnNextCh";
                } else if ("prev".equals(command)) {
                    id = "btnPrevCh";
                } else if ("ff".equals(command)) {
                    id = "btnFwd30";
                } else if ("rew".equals(command)) {
                    id = "btnBack15";
                } else {
                    return;
                }
                w.post(new Runnable() {
                    @Override
                    public void run() {
                        // The WebView may have been swapped (renderer
                        // recovery) or destroyed since this command was
                        // issued; touching the dead one can throw.
                        if (w != webView) {
                            return;
                        }
                        try {
                            w.evaluateJavascript("(function(){var b=document.getElementById('"
                                    + id + "');if(b)b.click();})()", null);
                        } catch (Exception e) {
                            // View went away mid-command: drop it.
                        }
                    }
                });
            }
        });

        // A launch share is consumed only on a truly fresh start: on a
        // recreation the same intent would otherwise add the link a
        // second time. After consuming, the intent is cleared so no
        // later recreation can replay it.
        String shared = savedInstanceState == null ? extractSharedUrl(getIntent()) : null;
        if (shared != null) {
            webView.loadUrl(HOME_URL + "?add=" + Uri.encode(shared));
            setIntent(new Intent(Intent.ACTION_MAIN));
        } else if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
            if (isShareIntent(getIntent())) {
                Toast.makeText(this, "Không thấy link trong nội dung chia sẻ",
                        Toast.LENGTH_SHORT).show();
            }
        }

        maybeAskBatteryExemption();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String shared = extractSharedUrl(intent);
        if (shared != null && webView != null) {
            webView.loadUrl(HOME_URL + "?add=" + Uri.encode(shared));
            setIntent(new Intent(Intent.ACTION_MAIN));
        } else if (isShareIntent(intent)) {
            Toast.makeText(this, "Không thấy link trong nội dung chia sẻ",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isShareIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_VIEW.equals(action);
    }

    /**
     * A YouTube (or any) link arriving via "Chia sẻ" (ACTION_SEND) or
     * "Mở bằng" (ACTION_VIEW). Returns the first http(s) link found, or
     * null for a normal launch. The web page reads it from ?add= and
     * files YouTube videos straight into the library.
     */
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile("https?://\\S+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private String extractSharedUrl(Intent intent) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        String candidate = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            candidate = data != null ? data.toString() : null;
        } else if (Intent.ACTION_SEND.equals(action)) {
            String text = null;
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                text = t.toString();
            }
            if (text == null) {
                CharSequence s = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                if (s != null) {
                    text = s.toString();
                }
            }
            if (text != null) {
                candidate = findUrl(text);
            }
        }
        if (candidate != null && candidate.startsWith(HOME_URL)) {
            return null; // our own page re-shared: nothing to add
        }
        return candidate;
    }

    /** First real URL in shared text, preferring a YouTube host when
     *  several links are present; sentence punctuation stuck to the end
     *  of a link is trimmed off. */
    private String findUrl(String text) {
        java.util.regex.Matcher m = URL_PATTERN.matcher(text);
        String first = null;
        while (m.find()) {
            String u = trimUrl(m.group());
            if (u == null) {
                continue;
            }
            if (first == null) {
                first = u;
            }
            String lower = u.toLowerCase(java.util.Locale.US);
            if (lower.contains("youtube.com/") || lower.contains("youtu.be/")) {
                return u;
            }
        }
        return first;
    }

    private String trimUrl(String u) {
        int end = u.length();
        while (end > 0 && ")]}>,.!?'\"".indexOf(u.charAt(end - 1)) >= 0) {
            end--;
        }
        return end > 8 ? u.substring(0, end) : null;
    }

    /**
     * One-time ask: battery optimisation is the number-one reason
     * background listening gets killed on real phones. If the app is not
     * exempt yet, explain in one sentence and offer the system's own
     * one-tap exemption dialog. Asked once, never nagged again.
     */
    private void maybeAskBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        final SharedPreferences prefs = getSharedPreferences("docsach", MODE_PRIVATE);
        if (prefs.getBoolean("batteryAsked", false)) {
            return;
        }
        prefs.edit().putBoolean("batteryAsked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Nghe chạy nền không bị ngắt")
                .setMessage("Một số điện thoại tự tắt ứng dụng chạy nền để tiết kiệm pin. "
                        + "Cho phép Đọc Sách không bị tối ưu pin thì nghe sách khi tắt "
                        + "màn hình sẽ liền mạch, không bị ngắt giữa chừng.")
                .setPositiveButton("Cho phép", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            // Some vendors hide that screen; the manual
                            // path is in the delivery note to the user.
                        }
                    }
                })
                .setNegativeButton("Để sau", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SYNC_FOLDER_REQUEST) {
            if (resultCode == RESULT_OK && SyncStore.acceptFolder(this, data)) {
                syncStatus("Đã chọn thư mục đồng bộ — thư viện sẽ tự ghi vào đó.");
                // The folder may already hold a backup from another
                // device: offer it to the page right away.
                checkCloudCopy();
            } else if (resultCode == RESULT_OK) {
                syncStatus("Không lấy được quyền ghi vào thư mục đã chọn.");
            }
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST) {
            return;
        }
        ValueCallback<Uri[]> callback = filePathCallback;
        filePathCallback = null;
        if (callback == null) {
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        callback.onReceiveValue(results);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        // Deliberately NO webView.onPause(): pausing the WebView suspends
        // its timers and media, which is exactly what would stop the
        // reading voice when the screen turns off. The foreground
        // service keeps the process alive; the page keeps playing.
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    /** Page → native bridge: playback-state reports (see NativePlayer)
     *  plus the cloud-sync surface. Every method is defensive: the page
     *  is a web document and may call these at any time. */
    private class NativeBridge {

        @JavascriptInterface
        public void postState(String json) {
            NativePlayer.updateFromJson(json);
        }

        /** Opens the system folder picker so the user can point the
         *  app at a folder inside Drive / OneDrive / MEGA on this
         *  phone. One pick; the permission persists. */
        @JavascriptInterface
        public void chooseSyncFolder() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivityForResult(SyncStore.pickFolderIntent(),
                                SYNC_FOLDER_REQUEST);
                    } catch (Exception e) {
                        syncStatus("Máy không mở được trình chọn thư mục.");
                    }
                }
            });
        }

        /** The page pushes its library backup JSON whenever the
         *  library changes; it lands in the sync folder. */
        @JavascriptInterface
        public void syncLibrary(String json) {
            if (json == null || json.length() == 0) {
                return;
            }
            SyncStore.writeLibrary(MainActivity.this, json, new SyncStore.Status() {
                @Override
                public void onStatus(String message) {
                    syncStatus(message);
                }
            });
        }

        /** Whether a sync folder is already chosen (page shows the
         *  right button label). */
        @JavascriptInterface
        public boolean hasSyncFolder() {
            return SyncStore.hasFolder(MainActivity.this);
        }

        /** Provider button from the page (SIMPLE UI). "mega": when the
         *  MEGA app is installed, open the system picker (MEGA shows up
         *  there with the account signed in on this phone); otherwise
         *  open MEGA's web so the user can sign in there. */
        @JavascriptInterface
        public void cloudButton(String provider) {
            if (!"mega".equals(provider)) return;
            boolean installed;
            try {
                getPackageManager().getPackageInfo("nz.mega.android", 0);
                installed = true;
            } catch (Exception e) {
                installed = false;
            }
            if (installed) {
                MainActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        WebView w = webView;
                        if (w != null) {
                            w.evaluateJavascript(
                                    "window.DocSachCloudPick&&window.DocSachCloudPick()",
                                    null);
                        }
                    }
                });
            } else {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://mega.nz")));
                } catch (Exception ignored) {
                }
                syncStatus("Chưa có ứng dụng MEGA trên máy — đã mở web MEGA."
                        + " Cài app MEGA rồi bấm lại để chọn sách trực tiếp.");
            }
        }

        /** Starts the on-device TTS engine (the unlimited fallback
         *  voice). Readiness and utterance events come back through
         *  window.DocSachNativeTtsEvent. */
        @JavascriptInterface
        public void ttsInit() {
            tts().init();
        }

        /** Speaks a queue of utterances; payload JSON from the page:
         *  {rate, lang, items:[{uid, text}]}. */
        @JavascriptInterface
        public void ttsSpeak(String json) {
            if (json != null) {
                tts().speak(json);
            }
        }

        /** Stops the on-device voice at once (pause / stop / chapter
         *  change on the page). */
        @JavascriptInterface
        public void ttsStop() {
            tts().stop();
        }

        /** Opens a cloud service's website in the phone's browser, so
         *  the user can sign in to ANOTHER account there. Restricted
         *  to the three services' own hosts. */
        @JavascriptInterface
        public void openWeb(final String url) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Uri uri = Uri.parse(url);
                        String host = uri.getHost() != null
                                ? uri.getHost().toLowerCase(java.util.Locale.US) : "";
                        if ("https".equals(uri.getScheme())
                                && (host.equals("drive.google.com")
                                || host.equals("onedrive.live.com")
                                || host.equals("mega.nz")
                                || host.equals("www.mega.nz"))) {
                            startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        }
                    } catch (Exception e) {
                        // Unknown host or no browser: ignore quietly.
                    }
                }
            });
        }
    }

    /** The shared on-device TTS engine, created on first use. Its
     *  events are forwarded into the page's native-voice hook. */
    private NativeTts tts() {
        if (nativeTts == null) {
            nativeTts = new NativeTts(this, new NativeTts.Sink() {
                @Override
                public void onEvent(String json) {
                    ttsPageEvent(json);
                }
            });
        }
        return nativeTts;
    }

    /** Delivers one native-TTS event (a JSON string) to the page. */
    private void ttsPageEvent(final String json) {
        final WebView w = webView;
        if (w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                if (w != webView) {
                    return;
                }
                try {
                    w.evaluateJavascript(
                            "window.DocSachNativeTtsEvent&&window.DocSachNativeTtsEvent("
                                    + org.json.JSONObject.quote(json) + ")", null);
                } catch (Exception e) {
                    // Page without the hook: nothing to deliver to.
                }
            }
        });
    }

    /** Reports a sync status line back into the page (its sync card),
     *  when the page provides the hook. */
    private void syncStatus(final String message) {
        final WebView w = webView;
        if (w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                if (w != webView) {
                    return;
                }
                try {
                    w.evaluateJavascript("window.DocSachSyncStatus&&window.DocSachSyncStatus("
                            + org.json.JSONObject.quote(message) + ")", null);
                } catch (Exception e) {
                    // Page without the hook: the toast-less native
                    // feedback is enough.
                }
            }
        });
    }

    /** If a sync folder is set and holds a library backup, hand it to
     *  the page, which merges it when it is newer than the local
     *  library. Called when the page finishes loading. */
    /* Friendly offline page for when the main page cannot load at all
       (no signal on the road, Wi-Fi dropped): without it the user just
       gets the WebView's blank built-in error screen and no way back.
       The retry button simply navigates to HOME_URL again. */
    private void showOfflinePage(WebView view) {
        String html = "<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<style>body{font-family:sans-serif;background:#111827;color:#f9fafb;display:flex;"
            + "align-items:center;justify-content:center;min-height:100vh;margin:0;text-align:center}"
            + ".box{padding:24px}h1{font-size:20px}p{color:#d1d5db;line-height:1.5}"
            + "a{display:inline-block;margin-top:16px;background:#2563eb;color:#fff;padding:12px 28px;"
            + "border-radius:10px;text-decoration:none;font-size:17px}</style></head><body>"
            + "<div class=\"box\"><h1>Không có kết nối mạng</h1>"
            + "<p>Ứng dụng cần mạng để mở thư viện sách.<br>"
            + "Hãy kiểm tra Wi-Fi hoặc dữ liệu di động rồi thử lại.</p>"
            + "<a href=\"" + HOME_URL + "\">Thử lại</a></div></body></html>";
        try {
            view.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            // If even this fails there is nothing sensible left to show.
        }
    }

    private void checkCloudCopy() {
        if (!SyncStore.hasFolder(this)) {
            return;
        }
        SyncStore.readLibrary(this, new SyncStore.ReadCallback() {
            @Override
            public void onRead(final String json, long lastModified) {
                if (json == null || json.length() == 0) {
                    return;
                }
                final WebView w = webView;
                if (w == null) {
                    return;
                }
                w.post(new Runnable() {
                    @Override
                    public void run() {
                        if (w != webView) {
                            return;
                        }
                        try {
                            w.evaluateJavascript(
                                    "window.DocSachSyncCheck&&window.DocSachSyncCheck("
                                            + org.json.JSONObject.quote(json) + ")", null);
                        } catch (Exception e) {
                            // Page without the hook: nothing to merge.
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (nativeTts != null) {
            nativeTts.shutdown();
            nativeTts = null;
        }
        NativePlayer.setSink(null);
        // The keeper service is stopped only on a real exit (Back at
        // the root, finish()). A system-initiated destruction — memory
        // pressure, a recreation the config flags did not cover — must
        // not tear down background playback.
        if (isFinishing()) {
            stopService(new Intent(this, PlaybackService.class));
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
