package com.chaiapps.memir;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * מנוע ההפרדה של האפליקציה: מריץ את מודל ההפרדה בקוד אנדרואיד (ONNX Runtime) על כמה ליבות.
 * הדף שולח חתיכה של השיר (אחרי שהכין אותה), כאן היא עוברת במודל, והתוצאה חוזרת לדף.
 */
class Nn {
    interface Tell { void js(String code); }

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static OrtEnvironment env;
    private static OrtSession session;
    private static String inputName;
    private static int threads;
    private static final Map<String, ByteBuffer> IN = new HashMap<>();
    private static final Map<String, byte[]> OUT = new HashMap<>();
    private static int seq = 0;

    static String sha1(String s) { return MainActivity.sha1(s); }

    /** the model: already on the phone, or downloaded once (with progress); then the engine is opened */
    static void prepare(Context c, String url, long size, Tell t) {
        EX.execute(() -> {
            try {
                if (session == null) {
                    File model = new File(c.getFilesDir(), "model-hq4.onnx");
                    if (!model.exists() || (size > 0 && model.length() != size)) {
                        File cached = new File(new File(c.getFilesDir(), "net"), sha1(url));   // the page may have downloaded it before
                        if (cached.exists() && (size <= 0 || cached.length() == size)) model = cached;
                        else download(url, model, size, t);
                    }
                    try {
                        open(model);
                    } catch (Throwable e) {
                        model.delete();   // a damaged copy: next time it is downloaded again
                        throw e;
                    }
                }
                t.js("window.__nn&&window.__nn('ready'," + threads + ")");
            } catch (Throwable e) {
                session = null;
                t.js("window.__nn&&window.__nn('err'," + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + ")");
            }
        });
    }

    private static void open(File model) throws Exception {
        env = OrtEnvironment.getEnvironment();
        int cores = Runtime.getRuntime().availableProcessors();
        threads = Math.max(2, Math.min(6, cores - 2));
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(threads);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(model.getAbsolutePath(), o);
        inputName = session.getInputNames().iterator().next();
    }

    private static void download(String url, File dst, long size, Tell t) throws Exception {
        File tmp = new File(dst.getPath() + ".tmp");
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            for (int hops = 0; ; hops++) {
                c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && hops < 8) { String loc = c.getHeaderField("Location"); c.disconnect(); u = new URL(u, loc); continue; }
                if (code != 200) throw new Exception("download " + code);
                break;
            }
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : size, done = 0;
            int last = -1;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int k;
                while ((k = in.read(buf)) > 0) {
                    o.write(buf, 0, k);
                    done += k;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (pct != last) { last = pct; t.js("window.__nn&&window.__nn('prog'," + pct + ")"); }
                }
            }
            if (size > 0 && tmp.length() != size) throw new Exception("download cut");
            if (!tmp.renameTo(dst)) throw new Exception("save");
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception e) { }
            tmp.delete();
        }
    }

    static synchronized String begin(int bytes) {
        if (session == null || bytes <= 0 || bytes > (64 << 20)) return "";
        String id = "n" + (++seq);
        IN.clear();   // one piece at a time; anything left over is from a stopped run
        IN.put(id, ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()));
        return id;
    }

    static boolean chunk(String id, String b64) {
        ByteBuffer b;
        synchronized (Nn.class) { b = IN.get(id); }
        if (b == null) return false;
        try { b.put(Base64.decode(b64, Base64.DEFAULT)); return true; } catch (Exception e) { return false; }
    }

    static void run(String id, int df, int dt, Tell t) {
        EX.execute(() -> {
            ByteBuffer b;
            synchronized (Nn.class) { b = IN.remove(id); }
            String err = null;
            try {
                if (b == null || session == null) throw new Exception("no input");
                b.flip();
                FloatBuffer fb = b.asFloatBuffer();
                try (OnnxTensor in = OnnxTensor.createTensor(env, fb, new long[] { 1, 4, df, dt });
                     OrtSession.Result r = session.run(Collections.singletonMap(inputName, in))) {
                    FloatBuffer of = ((OnnxTensor) r.get(0)).getFloatBuffer();
                    byte[] ob = new byte[of.remaining() * 4];
                    ByteBuffer.wrap(ob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(of);
                    synchronized (Nn.class) { OUT.clear(); OUT.put(id, ob); }
                }
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            t.js("window.__nnDone&&window.__nnDone('" + id + "'," + (err == null ? "null" : org.json.JSONObject.quote(err)) + ")");
        });
    }

    static synchronized byte[] take(String id) { return OUT.remove(id); }
}
