package com.fittracker.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Скачивает APK обновления внутри приложения и открывает системный установщик. */
@CapacitorPlugin(name = "ApkInstaller")
public class ApkInstallerPlugin extends Plugin {

    private File updDir() {
        File d = new File(getContext().getCacheDir(), "update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** При каждом запуске удаляем скачанные раньше файлы обновлений. */
    @Override
    public void load() {
        try {
            File[] fs = updDir().listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } catch (Exception ignored) {
        }
    }

    @PluginMethod
    public void install(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Некорректная ссылка", "BAD_URL");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getContext().getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {
            }
            call.reject("Нужно разрешение на установку приложений", "NEED_PERMISSION");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    File[] old = updDir().listFiles();
                    if (old != null) for (File o : old) o.delete();
                    File f = new File(updDir(), "update.apk");

                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    int code = c.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code);
                    long total = c.getContentLength();

                    InputStream in = c.getInputStream();
                    OutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[65536];
                    long got = 0;
                    int last = -1;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int p = (int) (got * 100 / total);
                            if (p != last) {
                                last = p;
                                JSObject o = new JSObject();
                                o.put("percent", p);
                                notifyListeners("progress", o);
                            }
                        }
                    }
                    out.flush();
                    out.close();
                    in.close();
                    if (got < 100000) throw new IOException("Файл слишком маленький");

                    Uri uri = FileProvider.getUriForFile(getContext(),
                            getContext().getPackageName() + ".fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);

                    JSObject r = new JSObject();
                    r.put("status", "started");
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()), "DOWNLOAD_FAILED");
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }
}
