package com.webapp.wrapper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_CODE = 1001;
    private static final int REQ_WEB_MEDIA = 2001;
    private static final int REQ_GEO = 2002;
    private static final int REQ_NOTIF = 2003;
    private static final int REQ_STORAGE = 2004;

    private static final String PERM_NOTIF = "android.permission.POST_NOTIFICATIONS";

    private WebView webView;
    private String realUrl = "";
    private ValueCallback<Uri[]> filePathCallback;

    // Izin yang DIDEKLARASIKAN di manifest (hasil whitelist prepare.py).
    // Izin yang tidak dideklarasikan tidak akan pernah diminta / diberikan ke halaman web.
    private final Set<String> declared = new HashSet<String>();

    private PermissionRequest pendingWebRequest;
    private List<String> pendingWebResources;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private Runnable pendingDownload;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadDeclaredPermissions();

        // Izin WAKE_LOCK aktif -> layar tetap menyala selama app dibuka
        if (isDeclared("android.permission.WAKE_LOCK")) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        webView = new WebView(this);
        setContentView(webView);

        realUrl = readAsset("www/url.txt").trim();

        // Jalur aman untuk buka file lokal (www/index.html) lewat https://appassets.androidplatform.net
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Hapus penanda "; wv" supaya situs dengan proteksi bot tidak menganggap ini WebView
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    return false; // tetap dibuka di dalam app
                }
                try { // tel:, mailto:, intent:, dll -> buka aplikasi lain
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !realUrl.isEmpty()) {
                    String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                            + "<body style='font-family:sans-serif;text-align:center;padding:40px'>"
                            + "<h2>Gagal memuat halaman</h2><p>Cek koneksi internet kamu.</p>"
                            + "<p><a href='" + TextUtils.htmlEncode(realUrl) + "'>Coba lagi</a></p></body></html>";
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // ---- File manager (input type=file) ----
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            // ---- Kamera / mikrofon (getUserMedia) ----
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleWebPermission(request);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebRequest == request) {
                    pendingWebRequest = null;
                    pendingWebResources = null;
                }
            }

            // ---- Lokasi (navigator.geolocation) ----
            @Override
            public void onGeolocationPermissionsShowPrompt(final String origin,
                                                           final GeolocationPermissions.Callback callback) {
                handleGeolocation(origin, callback);
            }
        });

        // ---- Unduhan file ----
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });

        // Android 13+: minta izin notifikasi sekali di awal (jika dideklarasikan)
        if (Build.VERSION.SDK_INT >= 33 && isDeclared(PERM_NOTIF) && !isGranted(PERM_NOTIF)) {
            requestPermissions(new String[]{PERM_NOTIF}, REQ_NOTIF);
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
        }
    }

    // ------------------------------------------------------------------
    // Helper izin
    // ------------------------------------------------------------------
    private void loadDeclaredPermissions() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (String p : info.requestedPermissions) declared.add(p);
            }
        } catch (Exception ignored) { }
    }

    private boolean isDeclared(String perm) {
        return declared.contains(perm);
    }

    private boolean isGranted(String perm) {
        if (Build.VERSION.SDK_INT < 23) return true; // di bawah Android 6 izin diberikan saat install
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Kamera / mikrofon dari halaman web
    // ------------------------------------------------------------------
    private void handleWebPermission(PermissionRequest request) {
        List<String> allowed = new ArrayList<String>();
        List<String> needAndroid = new ArrayList<String>();

        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                if (isDeclared(Manifest.permission.CAMERA)) {
                    allowed.add(r);
                    if (!isGranted(Manifest.permission.CAMERA)) needAndroid.add(Manifest.permission.CAMERA);
                }
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                if (isDeclared(Manifest.permission.RECORD_AUDIO)) {
                    allowed.add(r);
                    if (!isGranted(Manifest.permission.RECORD_AUDIO)) needAndroid.add(Manifest.permission.RECORD_AUDIO);
                }
            }
            // resource lain (DRM, MIDI, dll) sengaja ditolak
        }

        if (allowed.isEmpty()) {
            request.deny();
            return;
        }
        if (needAndroid.isEmpty()) {
            request.grant(allowed.toArray(new String[0]));
            return;
        }
        if (pendingWebRequest != null) pendingWebRequest.deny();
        pendingWebRequest = request;
        pendingWebResources = allowed;
        requestPermissions(needAndroid.toArray(new String[0]), REQ_WEB_MEDIA);
    }

    // ------------------------------------------------------------------
    // Lokasi dari halaman web
    // ------------------------------------------------------------------
    private boolean hasLocationPermission() {
        return isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
                || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void handleGeolocation(String origin, GeolocationPermissions.Callback callback) {
        if (!isDeclared(Manifest.permission.ACCESS_FINE_LOCATION)
                && !isDeclared(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            callback.invoke(origin, false, false);
            return;
        }
        if (hasLocationPermission()) {
            callback.invoke(origin, true, false);
            return;
        }
        if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
        pendingGeoCallback = callback;
        pendingGeoOrigin = origin;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
    }

    // ------------------------------------------------------------------
    // Unduhan
    // ------------------------------------------------------------------
    private void startDownload(final String url, final String ua, final String cd, final String mime) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("Jenis unduhan ini belum didukung di aplikasi");
            return;
        }
        final String write = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT < 29 && !isGranted(write)) {
            if (!isDeclared(write)) {
                toast("Izin penyimpanan tidak aktif");
                return;
            }
            pendingDownload = new Runnable() {
                @Override
                public void run() {
                    enqueueDownload(url, ua, cd, mime);
                }
            };
            requestPermissions(new String[]{write}, REQ_STORAGE);
            return;
        }
        enqueueDownload(url, ua, cd, mime);
    }

    private void enqueueDownload(String url, String ua, String cd, String mime) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (!TextUtils.isEmpty(mime)) req.setMimeType(mime);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (ua != null) req.addRequestHeader("User-Agent", ua);
            String fileName = URLUtil.guessFileName(url, cd, mime);
            req.setTitle(fileName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(req);
            toast("Mengunduh: " + fileName);
        } catch (Exception e) {
            toast("Unduhan gagal dimulai");
        }
    }

    // ------------------------------------------------------------------
    // Hasil permintaan izin
    // ------------------------------------------------------------------
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_WEB_MEDIA && pendingWebRequest != null) {
            List<String> ok = new ArrayList<String>();
            for (String r : pendingWebResources) {
                String p = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                        ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
                if (isGranted(p)) ok.add(r);
            }
            if (ok.isEmpty()) pendingWebRequest.deny();
            else pendingWebRequest.grant(ok.toArray(new String[0]));
            pendingWebRequest = null;
            pendingWebResources = null;

        } else if (requestCode == REQ_GEO && pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, hasLocationPermission(), false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;

        } else if (requestCode == REQ_STORAGE) {
            Runnable job = pendingDownload;
            pendingDownload = null;
            if (job != null) {
                if (isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) job.run();
                else toast("Unduhan dibatalkan: izin penyimpanan ditolak");
            }
        }
        // REQ_NOTIF: tidak ada aksi lanjutan
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
