package com.chaiapps.memir;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * סטודיו חכם – אפליקציה עצמאית.
 * כל קובצי האפליקציה יושבים בתוך ה-APK ונטענים מכתובת פנימית (appassets.androidplatform.net),
 * עם הכותרות שמאפשרות לעבוד על כמה ליבות. ספריות שהאפליקציה טוענת מהרשת (onnxruntime, lamejs…) ארוזות גם הן בפנים,
 * ומה שלא ארוז (הגופן, מודל ההפרדה) יורד פעם אחת ונשמר בטלפון.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST + "/";
    static final int REQ_FILE = 11, REQ_MIC = 12;

    private BgWebView web;
    private boolean working = false, shown = false;
    static final int REQ_NOTE = 13;

    /** while work is running the page is told it is still "visible", so the browser engine does not slow it down in the background */
    static class BgWebView extends WebView {
        boolean keep;
        BgWebView(android.content.Context c) { super(c); }
        @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(keep ? View.VISIBLE : v); }
        void sync() { super.onWindowVisibilityChanged(getWindowVisibility()); }
    }
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JSONObject> shared = new ArrayList<>();
    private final Map<String, Sink> sinks = new HashMap<>();
    private int sinkSeq = 0;

    static class Sink { String name, mime; File file; OutputStream out; }
    /** the film chosen for "shorten a video": the page shows it, the app cuts it from here */
    private volatile Uri vidUri;
    private volatile boolean vidExpect;

    // ------------------------------------------------------------------ start
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#181338"));
        web = new BgWebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // the clock bar and the navigation bar get their own strip; the app starts below / above them
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, bo;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = s.left; t = s.top; r = s.right; bo = s.bottom;
                v.setPadding(l, t, r, bo);
                return WindowInsets.CONSUMED;
            }
            l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop(); r = in.getSystemWindowInsetRight(); bo = in.getSystemWindowInsetBottom();
            v.setPadding(l, t, r, bo);
            return in.consumeSystemWindowInsets();
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Bridge(), "MemirApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> { /* downloads go through the bridge */ });

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::backKey);
        }
        takeShare(getIntent());
        web.loadUrl(BASE + "index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeShare(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { backKey(); }

    /** back: first closes an open window inside the app; on the main screen the app goes to the background (work keeps running) */
    private void backKey() {
        web.evaluateJavascript("(window.__memirBack&&window.__memirBack())?'1':'0'", v -> {
            if (v == null || !v.contains("1")) moveTaskToBack(true);
        });
    }

    @Override
    protected void onStart() {
        super.onStart(); shown = true;
        try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(8); } catch (Exception e) { }
    }

    @Override
    protected void onStop() { super.onStop(); shown = false; }

    /** work started / ended: the background service with its notification, and "finished" when the person is elsewhere */
    private void setWorking(boolean on) {
        if (on == working) return;
        working = on;
        if (web != null) { web.keep = on; if (!on) web.sync(); }
        try {
            Intent sv = new Intent(this, WorkService.class);
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        && !getPreferences(MODE_PRIVATE).getBoolean("askedNote", false)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("askedNote", true).apply();
                    requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTE);
                }
                startForegroundService(sv);
            } else {
                stopService(sv);
                if (!shown) {
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    nm.notify(8, WorkService.note(this, "סטודיו חכם", "העבודה הסתיימה ✓ געו כדי לחזור", false));
                }
            }
        } catch (Exception e) { }
    }

    @Override
    protected void onDestroy() {
        try { stopService(new Intent(this, WorkService.class)); } catch (Exception e) { }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    void js(String code) { ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    // ------------------------------------------------------------------ files served to the page
    class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            String host = u.getHost();
            if (host == null) return null;
            if (host.equals(HOST)) return local(u);
            if (!"GET".equalsIgnoreCase(req.getMethod())) return null;
            String path = u.getPath() == null ? "" : u.getPath();
            if (host.equals("cdn.jsdelivr.net") || host.equals("fonts.googleapis.com") || host.equals("fonts.gstatic.com")
                    || (host.equals("github.com") && path.contains("/releases/download/") && path.endsWith(".onnx")))
                return remote(u);
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
            return true;
        }
    }

    static Map<String, String> headers(boolean page) {
        Map<String, String> h = new HashMap<>();
        if (page) {
            // these two let the page use several cores at once (like the site did)
            h.put("Cross-Origin-Opener-Policy", "same-origin");
            h.put("Cross-Origin-Embedder-Policy", "require-corp");
            h.put("Cross-Origin-Resource-Policy", "same-origin");
            h.put("Cache-Control", "no-cache");
        } else {
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cross-Origin-Resource-Policy", "cross-origin");
            h.put("Cache-Control", "max-age=31536000");
        }
        return h;
    }

    static String mimeOf(String p) {
        String x = p.toLowerCase();
        if (x.endsWith(".html")) return "text/html";
        if (x.endsWith(".js") || x.endsWith(".mjs")) return "text/javascript";
        if (x.endsWith(".wasm")) return "application/wasm";
        if (x.endsWith(".json")) return "application/json";
        if (x.endsWith(".css")) return "text/css";
        if (x.endsWith(".png")) return "image/png";
        if (x.endsWith(".jpg") || x.endsWith(".jpeg")) return "image/jpeg";
        if (x.endsWith(".svg")) return "image/svg+xml";
        if (x.endsWith(".woff2")) return "font/woff2";
        String ext = MimeTypeMap.getFileExtensionFromUrl(x);
        String m = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    static WebResourceResponse resp(String mime, InputStream in, Map<String, String> h) {
        String enc = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript") ? "utf-8" : null;
        return new WebResourceResponse(mime, enc, 200, "OK", h, in);
    }

    static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(true), new ByteArrayInputStream(new byte[0]));
    }

    WebResourceResponse local(Uri u) {
        String p = u.getPath();
        if (p == null || p.equals("/") || p.isEmpty()) p = "/index.html";
        try {
            if (p.startsWith("/__shared/")) {
                File f = new File(new File(getCacheDir(), "shared"), new File(p.substring(10)).getName());
                if (!f.exists()) return notFound();
                Map<String, String> h = headers(true);
                h.put("Content-Length", String.valueOf(f.length()));
                return resp(mimeOf(f.getName()), new FileInputStream(f), h);
            }
            if (p.startsWith("/__nn/out/")) {   // the result of one piece from the app's separation engine
                byte[] ob = Nn.take(p.substring(10));
                if (ob == null) return notFound();
                Map<String, String> h = headers(true);
                h.put("Content-Length", String.valueOf(ob.length));
                h.put("Cache-Control", "no-store");
                return resp("application/octet-stream", new ByteArrayInputStream(ob), h);
            }
            if (p.startsWith("/models/") || p.equals("/sw.js")) return notFound();
            return resp(mimeOf(p), getAssets().open("www" + p), headers(true));
        } catch (IOException e) {
            return notFound();
        }
    }

    static String sha1(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            StringBuilder b = new StringBuilder();
            for (byte x : d.digest(s.getBytes("UTF-8"))) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return String.valueOf(s.hashCode()); }
    }

    /** a library or the model from the web: packed in the app, or kept on the phone after the first download */
    WebResourceResponse remote(Uri u) {
        String url = u.toString();
        Map<String, String> h = headers(false);
        if (u.getQuery() == null) {
            try {
                InputStream in = getAssets().open("cdn/" + u.getHost() + u.getPath());
                return resp(mimeOf(u.getPath()), in, h);
            } catch (IOException e) { /* not packed: from the phone or the web */ }
        }
        File dir = new File(getFilesDir(), "net");
        if (!dir.exists()) dir.mkdirs();
        String key = sha1(url);
        final File body = new File(dir, key), type = new File(dir, key + ".type");
        try {
            if (body.exists() && type.exists()) {
                String mime = readSmall(type);
                h.put("Content-Length", String.valueOf(body.length()));
                return resp(mime, new FileInputStream(body), h);
            }
            HttpURLConnection c = null;
            URL cur = new URL(url);
            for (int hops = 0; ; hops++) {
                c = (HttpURLConnection) cur.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && hops < 8) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    cur = new URL(cur, loc);
                    continue;
                }
                if (code != 200) { c.disconnect(); return null; }
                break;
            }
            String ct = c.getContentType();
            String mime = ct == null ? mimeOf(u.getPath() == null ? "" : u.getPath()) : ct.split(";")[0].trim();
            long len = c.getContentLengthLong();
            if (len > 0) h.put("Content-Length", String.valueOf(len));
            final File tmp = new File(dir, key + ".tmp");
            final String fmime = mime;
            final HttpURLConnection fc = c;
            final long flen = len;
            // the page reads it while it is being saved on the phone; a complete copy is kept for next time
            InputStream tee = new FilterInputStream(c.getInputStream()) {
                final OutputStream fo = new FileOutputStream(tmp);
                long got = 0; boolean done = false;
                @Override public int read() throws IOException { byte[] one = new byte[1]; int k = read(one, 0, 1); return k < 0 ? -1 : one[0] & 255; }
                @Override public int read(byte[] b, int off, int n) throws IOException {
                    int k = super.read(b, off, n);
                    if (k > 0) { fo.write(b, off, k); got += k; }
                    else if (k < 0 && !done) { done = true; fo.close(); if (flen <= 0 || got == flen) { writeSmall(type, fmime); tmp.renameTo(body); } else tmp.delete(); }
                    return k;
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { if (!done) { done = true; try { fo.close(); } catch (IOException e) { } tmp.delete(); } fc.disconnect(); }
                }
            };
            return resp(mime, tee, h);
        } catch (Exception e) {
            return null;
        }
    }

    static String readSmall(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(4096, f.length())];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), "UTF-8").trim();
        }
    }

    static void writeSmall(File f, String s) {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); } catch (IOException e) { }
    }

    // ------------------------------------------------------------------ choosing files, the microphone
    class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            List<String> mimes = new ArrayList<>();
            String[] acc = p.getAcceptTypes();
            if (acc != null) for (String a : acc) for (String x : a.split(",")) {
                x = x.trim();
                if (x.isEmpty()) continue;
                if (x.startsWith(".")) {
                    String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(x.substring(1).toLowerCase());
                    if (m != null && !mimes.contains(m)) mimes.add(m);
                } else if (!mimes.contains(x)) mimes.add(x);
            }
            i.setType("*/*");
            if (!mimes.isEmpty() && !mimes.contains("*/*")) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
            if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(i, REQ_FILE); }
            catch (Exception e) { fileCb = null; cb.onReceiveValue(null); return false; }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest r) {
            ui.post(() -> {
                boolean mic = false;
                for (String x : r.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x)) mic = true;
                if (!mic) { r.deny(); return; }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    r.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                } else {
                    pendingPerm = r;
                    requestPermissions(new String[] { android.Manifest.permission.RECORD_AUDIO }, REQ_MIC);
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC && pendingPerm != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
                pendingPerm.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_FILE || fileCb == null) return;
        Uri[] out = null;
        if (result == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null && cd.getItemCount() > 0) {
                out = new Uri[cd.getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) out = new Uri[] { data.getData() };
        }
        if (vidExpect) { vidExpect = false; vidUri = (out != null && out.length > 0) ? out[0] : null; }
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    // ------------------------------------------------------------------ "share" into the app (WhatsApp, gallery…)
    @SuppressWarnings("deprecation")
    private void takeShare(Intent i) {
        if (i == null || i.getAction() == null) return;
        final List<Uri> uris = new ArrayList<>();
        String a = i.getAction();
        try {
            if (Intent.ACTION_SEND.equals(a)) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); }
            else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) { ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
            else if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) uris.add(i.getData());
        } catch (Exception e) { }
        if (uris.isEmpty()) return;
        i.setAction(Intent.ACTION_MAIN);
        final String mime = i.getType();
        new Thread(() -> {
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists()) dir.mkdirs();
            synchronized (shared) {
                if (shared.isEmpty()) { File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete(); }
            }
            int n = 0;
            for (Uri u : uris) {
                try {
                    String name = displayName(u);
                    String safe = (System.currentTimeMillis() + "_" + (n++) + "_" + name).replaceAll("[\\\\/:*?\"<>|]", "_");
                    File f = new File(dir, safe);
                    try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(f)) {
                        byte[] buf = new byte[1 << 16]; int k;
                        while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                    }
                    String t = getContentResolver().getType(u);
                    JSONObject j = new JSONObject();
                    j.put("name", name);
                    j.put("type", t != null ? t : (mime != null ? mime : ""));
                    j.put("url", BASE + "__shared/" + Uri.encode(safe));
                    synchronized (shared) { shared.add(j); }
                } catch (Exception e) { }
            }
            js("window.__memirOnShare&&window.__memirOnShare()");
        }).start();
    }

    private String displayName(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception e) { }
        if (n == null || n.isEmpty()) n = u.getLastPathSegment();
        if (n == null || n.isEmpty()) n = "shared";
        return n;
    }

    // ------------------------------------------------------------------ what the page can ask for
    class Bridge {
        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
        }

        @JavascriptInterface
        public String takeShared() {
            synchronized (shared) {
                JSONArray a = new JSONArray();
                for (JSONObject j : shared) a.put(j);
                shared.clear();
                return a.toString();
            }
        }

        // ---- the app's separation engine (Android code, several cores)
        @JavascriptInterface
        public void nnPrepare(String url, String size) {
            long n = 0; try { n = Long.parseLong(size); } catch (Exception e) { }
            Nn.prepare(getApplicationContext(), url, n, MainActivity.this::js);
        }

        @JavascriptInterface
        public String nnBegin(int bytes) { try { return Nn.begin(bytes); } catch (Throwable e) { return ""; } }

        @JavascriptInterface
        public boolean nnChunk(String id, String b64) { return Nn.chunk(id, b64); }

        @JavascriptInterface
        public void nnRun(String id, int df, int dt) { Nn.run(id, df, dt, MainActivity.this::js); }

        @JavascriptInterface
        public void keepAwake(boolean on) {
            ui.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setWorking(on);
            });
        }

        @JavascriptInterface
        public void toast(String msg) { ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show()); }

        @JavascriptInterface
        public void openUrl(String url) {
            ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { } });
        }

        /** a file made in the app, sent in pieces: then saved in "Downloads", or shared */
        @JavascriptInterface
        public String saveBegin(String name, String mime) {
            try {
                File dir = new File(getCacheDir(), "out");
                if (!dir.exists()) dir.mkdirs();
                Sink s = new Sink();
                s.name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                s.mime = (mime == null || mime.isEmpty()) ? mimeOf(s.name) : mime;
                s.file = new File(dir, "part" + (++sinkSeq));
                s.out = new FileOutputStream(s.file);
                String id = "s" + sinkSeq;
                synchronized (sinks) { sinks.put(id, s); }
                return id;
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean saveChunk(String id, String b64) {
            Sink s; synchronized (sinks) { s = sinks.get(id); }
            if (s == null) return false;
            try { s.out.write(Base64.decode(b64, Base64.DEFAULT)); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String saveEnd(String id, String mode) {
            Sink s; synchronized (sinks) { s = sinks.remove(id); }
            if (s == null) return "";
            try { s.out.close(); } catch (IOException e) { }
            try {
                if ("share".equals(mode)) {
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete();
                    File f = new File(dir, s.name);
                    if (!s.file.renameTo(f)) return "";
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(s.mime);
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent ch = Intent.createChooser(i, null);
                    ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ui.post(() -> { try { startActivity(ch); } catch (Exception e) { } });
                    return s.name;
                }
                String saved = toDownloads(s);
                s.file.delete();
                return saved;
            } catch (Exception e) {
                s.file.delete();
                return "";
            }
        }

        /** the next file the page asks for is the film to shorten */
        @JavascriptInterface
        public void vidExpect() { vidExpect = true; vidUri = null; }

        @JavascriptInterface
        public boolean vidReady() { return vidUri != null; }

        /** cut the chosen film between two times (seconds) and save it to Downloads, or open the share menu */
        @JavascriptInterface
        public void vidTrim(final double startSec, final double endSec, final String name, final String mode) {
            final Uri u = vidUri;
            if (u == null) { js("window.__vid&&window.__vid('err','nofile')"); return; }
            new Thread(() -> {
                File dir = new File(getCacheDir(), "out");
                if (!dir.exists()) dir.mkdirs();
                int seq; synchronized (sinks) { seq = ++sinkSeq; }
                File f = new File(dir, "vid" + seq + ".mp4");
                try {
                    Vid.trim(MainActivity.this, u, f, (long) (startSec * 1e6), (long) (endSec * 1e6),
                            pct -> js("window.__vid&&window.__vid('prog'," + pct + ")"));
                    Sink s = new Sink();
                    s.name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                    s.mime = "video/mp4";
                    s.file = f;
                    s.out = new java.io.ByteArrayOutputStream();
                    String id = "v" + seq;
                    synchronized (sinks) { sinks.put(id, s); }
                    String saved = saveEnd(id, "share".equals(mode) ? "share" : "save");
                    if (saved == null || saved.isEmpty()) js("window.__vid&&window.__vid('err','save')");
                    else js("window.__vid&&window.__vid('done'," + JSONObject.quote(saved) + ")");
                } catch (Throwable e) {
                    f.delete();
                    js("window.__vid&&window.__vid('err'," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void updateDownload(String url) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        Intent st = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                        ui.post(() -> { try { startActivity(st); } catch (Exception e) { } });
                        js("window.__memirUpd&&window.__memirUpd('perm')");
                        return;
                    }
                    File dir = new File(getCacheDir(), "upd");
                    if (!dir.exists()) dir.mkdirs();
                    File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete();
                    File out = new File(dir, "memir.apk");
                    URL u = new URL(url);
                    for (int hops = 0; ; hops++) {
                        c = (HttpURLConnection) u.openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(20000); c.setReadTimeout(60000);
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400 && hops < 8) { String loc = c.getHeaderField("Location"); c.disconnect(); u = new URL(u, loc); continue; }
                        if (code != 200) throw new IOException("http " + code);
                        break;
                    }
                    long total = c.getContentLengthLong(), done = 0; int last = -1;
                    try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16]; int k;
                        while ((k = in.read(buf)) > 0) {
                            o.write(buf, 0, k); done += k;
                            int pct = total > 0 ? (int) (done * 100 / total) : -1;
                            if (pct != last) { last = pct; js("window.__memirUpd&&window.__memirUpd(" + pct + ")"); }
                        }
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", out);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    ui.post(() -> { try { startActivity(i); } catch (Exception e) { } });
                    js("window.__memirUpd&&window.__memirUpd('done')");
                } catch (Exception e) {
                    js("window.__memirUpd&&window.__memirUpd('err')");
                } finally { if (c != null) try { c.disconnect(); } catch (Exception e) { } }
            }).start();
        }
    }

    /** into the phone's "Downloads" folder, where the gallery, WhatsApp and the file manager see it */
    @SuppressWarnings("deprecation")
    private String toDownloads(Sink s) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, s.name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, s.mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (item == null) throw new IOException("insert");
            try (InputStream in = new FileInputStream(s.file); OutputStream o = cr.openOutputStream(item)) {
                if (o == null) throw new IOException("open");
                byte[] buf = new byte[1 << 16]; int k;
                while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, done, null, null);
            String name = s.name;
            try (Cursor c = cr.query(item, new String[] { MediaStore.MediaColumns.DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception e) { }
            return name;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, s.name);
        for (int k = 2; f.exists(); k++) {
            int dot = s.name.lastIndexOf('.');
            f = new File(dir, dot > 0 ? s.name.substring(0, dot) + " (" + k + ")" + s.name.substring(dot) : s.name + " (" + k + ")");
        }
        try (InputStream in = new FileInputStream(s.file); OutputStream o = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16]; int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
        }
        return f.getName();
    }
}
