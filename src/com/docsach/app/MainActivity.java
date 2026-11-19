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
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
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

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // The page chains short audio chunks; without this, WebView can
        // refuse to start the next chunk while the app is backgrounded.
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url != null ? url.getScheme() : null;
                return !("http".equals(scheme) || "https".equals(scheme));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
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

        String shared = extractSharedUrl(getIntent());
        if (shared != null) {
            webView.loadUrl(HOME_URL + "?add=" + Uri.encode(shared));
        } else if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
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
        }
    }

    /**
     * A YouTube (or any) link arriving via "Chia sẻ" (ACTION_SEND) or
     * "Mở bằng" (ACTION_VIEW). Returns the first http(s) link found, or
     * null for a normal launch. The web page reads it from ?add= and
     * files YouTube videos straight into the library.
     */
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
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                for (String part : text.toString().split("\\s+")) {
                    if (part.startsWith("http://") || part.startsWith("https://")) {
                        candidate = part;
                        break;
                    }
                }
            }
        }
        if (candidate != null && candidate.startsWith(HOME_URL)) {
            return null; // our own page re-shared: nothing to add
        }
        return candidate;
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

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, PlaybackService.class));
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
