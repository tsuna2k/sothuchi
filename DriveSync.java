package com.hanhholmes.sothuchi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class DriveSync {
    // === ĐIỀN 2 DÒNG NÀY (OAuth client loại "Web application", dùng chung với bản web) ===
    static final String CLIENT_ID = "XXXX.apps.googleusercontent.com";
    static final String CLIENT_SECRET = "XXXX";
    // =========================================================
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String FILE_NAME = "so-tai-chinh-latest.sqlite";
    static final String MIME = "application/x-sqlite3";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    // Phải khai báo đúng địa chỉ này trong "Authorized redirect URIs" của client:  http://127.0.0.1:8765
    // (nếu Google không nhận 127.0.0.1 thì đổi REDIRECT_HOST thành "localhost" và khai báo http://localhost:8765)
    static final int PORT = 8765;
    static final String REDIRECT_HOST = "127.0.0.1";
    static ServerSocket cur;

    interface Done { void run(boolean ok, String msg); }
    static class Resp { int code; byte[] body; }

    final SharedPreferences sp;

    DriveSync(Context c) { sp = c.getSharedPreferences("drive", 0); }

    boolean connected() { return sp.getString("rt", "").length() > 0; }
    /** true nếu trước đó đã kết nối nhưng Google đã thu hồi / hết hạn quyền */
    boolean expired() { return !connected() && sp.getBoolean("expired", false); }
    void disconnect() { sp.edit().clear().apply(); }

    // ---------- Đăng nhập (trình duyệt hệ thống + PKCE + loopback) ----------
    String startAuth(final Done cb) throws Exception {
        if (cur != null) { try { cur.close(); } catch (Exception ignore) {} }
        final ServerSocket ss = new ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1"));
        cur = ss;
        ss.setSoTimeout(180000);
        final String redirect = "http://" + REDIRECT_HOST + ":" + PORT;
        final String verifier = rnd();
        final String state = rnd().substring(0, 16);
        String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")));
        String url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(redirect) + "&response_type=code&scope=" + enc(SCOPE)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256"
                + "&state=" + state + "&access_type=offline&prompt=consent";
        new Thread(new Runnable() { public void run() {
            try {
                String code = null, st = null, err = null;
                for (int i = 0; i < 10 && code == null && err == null; i++) {
                    Socket s = ss.accept();
                    s.setSoTimeout(5000);
                    String line = null;
                    try { line = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine(); }
                    catch (Exception ignore) {}
                    if (line != null && line.indexOf(' ') > 0) {
                        String q = line.split(" ")[1];
                        Uri u = Uri.parse("http://127.0.0.1" + q);
                        code = u.getQueryParameter("code");
                        st = u.getQueryParameter("state");
                        err = u.getQueryParameter("error");
                        byte[] bb = ("<html><meta charset=utf-8><body style='font-family:sans-serif;text-align:center;margin-top:20vh'>"
                                + "<h2>Xong! Hãy quay lại app Sổ Tài Chính.</h2></body></html>").getBytes("UTF-8");
                        OutputStream o = s.getOutputStream();
                        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                                + bb.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                        o.write(bb); o.flush();
                    }
                    s.close();
                }
                ss.close();
                if (code == null || !state.equals(st)) { cb.run(false, "Đăng nhập Drive bị huỷ"); return; }
                Resp r = form(TOKEN_URL, "code=" + enc(code) + "&client_id=" + enc(CLIENT_ID)
                        + "&client_secret=" + enc(CLIENT_SECRET) + "&redirect_uri=" + enc(redirect)
                        + "&grant_type=authorization_code&code_verifier=" + enc(verifier));
                JSONObject j = new JSONObject(new String(r.body, "UTF-8"));
                if (r.code != 200 || !j.has("refresh_token"))
                    throw new Exception(j.optString("error_description", "HTTP " + r.code));
                sp.edit().putString("rt", j.getString("refresh_token")).putBoolean("expired", false).apply();
                cb.run(true, "Đã kết nối Google Drive");
            } catch (Exception e) {
                try { ss.close(); } catch (Exception ignore) {}
                if (ss != cur) return; // lần kết nối cũ đã bị thay bằng lần mới
                cb.run(false, "Kết nối Drive lỗi: " + e.getMessage());
            }
        }}).start();
        return url;
    }

    String accessToken() throws Exception {
        String rt = sp.getString("rt", "");
        if (rt.length() == 0) throw new Exception("Chưa kết nối Drive");
        Resp r = form(TOKEN_URL, "client_id=" + enc(CLIENT_ID) + "&client_secret=" + enc(CLIENT_SECRET)
                + "&refresh_token=" + enc(rt) + "&grant_type=refresh_token");
        JSONObject j = new JSONObject(new String(r.body, "UTF-8"));
        if (r.code != 200) {
            if ("invalid_grant".equals(j.optString("error"))) {
                sp.edit().remove("rt").putBoolean("expired", true).apply();
                throw new Exception("Phiên Drive đã hết hạn, hãy kết nối lại");
            }
            throw new Exception(j.optString("error_description", "HTTP " + r.code));
        }
        return j.getString("access_token");
    }

    // ---------- Drive: ghi đè file latest (không xoá trước để không mất bản cũ nếu lỗi mạng) ----------
    void upload(byte[] data) throws Exception {
        String tok = accessToken();
        String id = sp.getString("fid", "");
        if (id.length() > 0 && update(tok, id, data)) return;
        sp.edit().remove("fid").apply();
        id = findId(tok);
        if (id != null && update(tok, id, data)) return;

        String b = "sotc" + System.currentTimeMillis();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length + 512);
        bo.write(("--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\""
                + FILE_NAME + "\"}\r\n--" + b + "\r\nContent-Type: " + MIME + "\r\n\r\n").getBytes("UTF-8"));
        bo.write(data);
        bo.write(("\r\n--" + b + "--").getBytes("UTF-8"));
        Resp r = http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                new String[][]{{"Authorization", "Bearer " + tok},
                        {"Content-Type", "multipart/related; boundary=" + b}}, bo.toByteArray());
        if (r.code != 200) throw fail(r);
        sp.edit().putString("fid", new JSONObject(new String(r.body, "UTF-8")).getString("id")).apply();
    }

    /** true nếu cập nhật được; false nếu file không còn (404) hoặc đang nằm trong thùng rác */
    boolean update(String tok, String id, byte[] data) throws Exception {
        Resp r = http("POST", "https://www.googleapis.com/upload/drive/v3/files/" + id
                        + "?uploadType=media&fields=id,trashed",
                new String[][]{{"Authorization", "Bearer " + tok}, {"Content-Type", MIME},
                        {"X-HTTP-Method-Override", "PATCH"}}, data);
        if (r.code == 404) return false;
        if (r.code != 200) throw fail(r);
        JSONObject j = new JSONObject(new String(r.body, "UTF-8"));
        if (j.optBoolean("trashed", false)) return false;
        sp.edit().putString("fid", id).apply();
        return true;
    }

    String findId(String tok) throws Exception {
        String q = "name='" + FILE_NAME + "' and trashed=false";
        Resp r = http("GET", "https://www.googleapis.com/drive/v3/files?q=" + enc(q)
                        + "&fields=files(id)&orderBy=" + enc("modifiedTime desc") + "&pageSize=1",
                new String[][]{{"Authorization", "Bearer " + tok}}, null);
        if (r.code != 200) throw fail(r);
        JSONArray a = new JSONObject(new String(r.body, "UTF-8")).getJSONArray("files");
        return a.length() > 0 ? a.getJSONObject(0).getString("id") : null;
    }

    /** Trả về null nếu trên Drive chưa có file backup */
    byte[] download() throws Exception {
        String tok = accessToken();
        String id = sp.getString("fid", "");
        if (id.length() == 0) id = findId(tok);
        if (id == null) return null;
        Resp r = http("GET", "https://www.googleapis.com/drive/v3/files/" + id + "?alt=media",
                new String[][]{{"Authorization", "Bearer " + tok}}, null);
        if (r.code == 404) {
            sp.edit().remove("fid").apply();
            return null;
        }
        if (r.code != 200) throw fail(r);
        return r.body;
    }

    // ---------- tiện ích HTTP ----------
    Resp form(String url, String body) throws Exception {
        return http("POST", url, new String[][]{{"Content-Type", "application/x-www-form-urlencoded"}},
                body.getBytes("UTF-8"));
    }

    Resp http(String method, String url, String[][] headers, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestMethod(method);
        for (int i = 0; i < headers.length; i++) c.setRequestProperty(headers[i][0], headers[i][1]);
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.close();
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        r.body = bo.toByteArray();
        c.disconnect();
        return r;
    }

    Exception fail(Resp r) {
        String t = "";
        try { t = new String(r.body, "UTF-8"); } catch (Exception ignore) {}
        if (t.length() > 160) t = t.substring(0, 160);
        return new Exception("Drive HTTP " + r.code + " " + t);
    }

    static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    static String b64url(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }
    static String rnd() { byte[] b = new byte[48]; new SecureRandom().nextBytes(b); return b64url(b); }
}
