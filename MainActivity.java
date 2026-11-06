package com.hanhholmes.sothuchi;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.*;

public class MainActivity extends Activity {
    WebView web;
    ValueCallback<Uri[]> fileCb;
    DriveSync drive;
    static final int REQ_FILE = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        drive = new DriveSync(this);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().addFlags(0x80000000); // cho phép tự đặt màu thanh trạng thái
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBackup");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { checkDrive(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, d));
            fileCb = null;
        }
    }

    // ---------- cầu nối JS <-> Android ----------
    class Bridge {
        @JavascriptInterface public void saveBackup(final String name, final String b64, final String mime) {
            new Thread(new Runnable() { public void run() { doSave(name, b64, mime); } }).start();
        }
        @JavascriptInterface public String driveStatus() {
            return drive.connected() ? "connected" : (drive.expired() ? "expired" : "disconnected");
        }
        @JavascriptInterface public void driveDisconnect() { drive.disconnect(); }
        @JavascriptInterface public void driveConnect() {
            try {
                if (DriveSync.CLIENT_ID.startsWith("XXXX")) { result(false, "Chưa điền CLIENT_ID trong DriveSync.java"); return; }
                final String url = drive.startAuth(new DriveSync.Done() {
                    public void run(boolean ok, String msg) { result(ok, msg); js("window.onDriveState&&window.onDriveState()"); }
                });
                runOnUiThread(new Runnable() { public void run() {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                }});
            } catch (Exception e) { result(false, "Lỗi: " + e.getMessage()); }
        }
        @JavascriptInterface public void driveRestore() {
            new Thread(new Runnable() { public void run() {
                try {
                    byte[] data = drive.download();
                    if (data == null) { result(false, "Trên Drive chưa có file backup"); return; }
                    js("window.onDriveSqlite(" + JSONObject.quote(Base64.encodeToString(data, Base64.NO_WRAP)) + ")");
                } catch (Exception e) { result(false, "Khôi phục Drive lỗi: " + e.getMessage()); }
            }}).start();
        }
        /** Đồng bộ: tải bản Drive về (chuỗi rỗng nếu chưa có) để JS gộp rồi ghi lại */
        @JavascriptInterface public void driveFetch() {
            new Thread(new Runnable() { public void run() {
                try {
                    byte[] data = drive.download();
                    String b64 = data == null ? "" : Base64.encodeToString(data, Base64.NO_WRAP);
                    js("window.onDriveSyncData(" + JSONObject.quote(b64) + ")");
                } catch (Exception e) { result(false, "Đồng bộ Drive lỗi: " + e.getMessage()); }
            }}).start();
        }
        /** Đổi màu thanh trạng thái / thanh điều hướng theo giao diện sáng-tối */
        @JavascriptInterface public void setDark(final boolean dark) {
            runOnUiThread(new Runnable() { public void run() {
                int c = dark ? Color.BLACK : 0xFFF4F6FB;
                web.setBackgroundColor(c);
                if (Build.VERSION.SDK_INT >= 21) {
                    getWindow().setStatusBarColor(c);
                    getWindow().setNavigationBarColor(c);
                }
                if (Build.VERSION.SDK_INT >= 23) {
                    int flags = 0x2000 | (Build.VERSION.SDK_INT >= 26 ? 0x10 : 0); // biểu tượng sáng/tối
                    int f = getWindow().getDecorView().getSystemUiVisibility();
                    getWindow().getDecorView().setSystemUiVisibility(dark ? (f & ~flags) : (f | flags));
                }
            }});
        }
    }

    /** Mở app: nếu đã kết nối thì thử làm mới quyền, nếu Google đã thu hồi thì hiện nút Kết nối lại */
    void checkDrive() {
        if (!drive.connected()) return;
        new Thread(new Runnable() { public void run() {
            try { drive.accessToken(); } catch (Exception ignore) {}
            js("window.onDriveState&&window.onDriveState()");
        }}).start();
    }

    // ---------- lưu backup ----------
    void doSave(String name, String b64, String mime) {
        byte[] data;
        try { data = Base64.decode(b64, Base64.DEFAULT); }
        catch (Exception e) { result(false, "Dữ liệu backup lỗi"); return; }
        boolean isSql = name.endsWith(".sqlite"), isJson = name.endsWith(".json");
        String outName = name;
        String msg = "";
        boolean ok = true;

        // 1) bản "latest" cố định trong bộ nhớ riêng của app
        if (isSql || isJson) {
            String ext = isSql ? ".sqlite" : ".json";
            outName = "so-tai-chinh-latest" + ext;
            try {
                File dir = new File(getFilesDir(), "backup");
                dir.mkdirs();
                File tmp = new File(dir, "latest" + ext + ".tmp"), fin = new File(dir, "latest" + ext);
                FileOutputStream o = new FileOutputStream(tmp);
                o.write(data); o.close();
                fin.delete();
                tmp.renameTo(fin);
                msg += "Đã lưu bản latest trong app. ";
            } catch (Exception e) { ok = false; msg += "Lưu trong app lỗi: " + e.getMessage() + ". "; }
        }
        // 2) 1 bản sao vào Downloads cho người dùng xem
        try { saveToDownloads(outName, mime, data); msg += "Đã chép vào Downloads."; }
        catch (Exception e) { ok = false; msg += "Chép Downloads lỗi: " + e.getMessage(); }

        // 3) chỉ .sqlite: ghi đè bản latest trên Drive (nếu đã kết nối)
        boolean toDrive = isSql && drive.connected();
        result(ok, msg + (toDrive ? " Đang đẩy lên Drive..." : ""));
        if (toDrive) {
            try { drive.upload(data); result(true, "Đã cập nhật bản latest trên Drive"); }
            catch (Exception e) { result(false, "Đẩy lên Drive lỗi: " + e.getMessage() + "\n(Bản trong app và Downloads vẫn an toàn)"); }
            js("window.onDriveState&&window.onDriveState()");
        }
    }

    void saveToDownloads(String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            Uri base = Uri.parse("content://media/external/downloads");
            Cursor c = cr.query(base, new String[]{"_id"}, "_display_name=? AND relative_path=?",
                    new String[]{name, "Download/"}, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        try { cr.delete(ContentUris.withAppendedId(base, c.getLong(0)), null, null); }
                        catch (Exception ignore) {}
                    }
                } finally { c.close(); }
            }
            ContentValues v = new ContentValues();
            v.put("_display_name", name);
            v.put("mime_type", mime);
            v.put("relative_path", "Download/");
            v.put("is_pending", 1);
            Uri u = cr.insert(base, v);
            if (u == null) throw new IOException("không tạo được file");
            OutputStream o = cr.openOutputStream(u);
            o.write(data); o.close();
            ContentValues d = new ContentValues();
            d.put("is_pending", 0);
            cr.update(u, d, null, null);
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            FileOutputStream o = new FileOutputStream(new File(dir, name));
            o.write(data); o.close();
        }
    }

    // ---------- gọi ngược về JS ----------
    void result(boolean ok, String msg) {
        js("window.onNativeBackupResult(" + ok + "," + JSONObject.quote(msg) + ")");
    }
    void js(final String code) {
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript(code, null); } });
    }
}
