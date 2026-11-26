package com.z7ui.pinswipe;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检查更新：读 z7ui.cn 上的统一清单，比 versionCode。
 * 只有远端 versionCode 比本机大才算「有新版本」。
 */
public final class Updater {

    private static final String MANIFEST = "https://www.z7ui.cn/apps-archive/version.json";
    private static final String APK_BASE = "https://www.z7ui.cn/apps-archive/downloads/";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final class Info {
        public final String version;
        public final int versionCode;
        public final String notes;
        public final String apkUrl;

        Info(String version, int versionCode, String notes, String apkUrl) {
            this.version = version;
            this.versionCode = versionCode;
            this.notes = notes == null ? "" : notes;
            this.apkUrl = apkUrl;
        }
    }

    public interface Callback {
        /** info 为 null：已是最新，或网络/解析失败 */
        void onDone(Info info);
    }

    private Updater() {
    }

    public static void check(Context ctx, Callback cb) {
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            final Info info = fetch(app);
            MAIN.post(() -> cb.onDone(info));
        });
    }

    private static Info fetch(Context ctx) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MANIFEST).openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "z7ui-updater");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject root = new JSONObject(sb.toString());
            JSONObject apps = root.optJSONObject("apps");
            if (apps == null) {
                return null;
            }
            JSONObject me = apps.optJSONObject(AppMeta.KEY);
            if (me == null) {
                return null;
            }

            int remote = me.optInt("versionCode", 0);
            int current = currentVersionCode(ctx);
            String apk = me.optString("apk", "");
            if (remote <= current || apk.isEmpty()) {
                return null;
            }
            return new Info(
                    me.optString("version", ""),
                    remote,
                    me.optString("notes", ""),
                    APK_BASE + apk);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static int currentVersionCode(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            String v = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 打开链接，返回是否成功（没有浏览器时给调用方处理） */
    public static boolean openUrl(Context c, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
