package com.benmaty.hlsdownloader;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private List<String> history = new ArrayList<>();
    private ArrayAdapter<String> historyAdapter;
    private File lastDownloadedFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText urlInput    = findViewById(R.id.urlInput);
        Button btnDownload   = findViewById(R.id.btnDownload);
        Button btnPaste      = findViewById(R.id.btnPaste);
        Button btnOpen       = findViewById(R.id.btnOpen);
        TextView status      = findViewById(R.id.status);
        ProgressBar pb       = findViewById(R.id.progressBar);
        ListView historyList = findViewById(R.id.historyList);

        historyAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, history);
        historyList.setAdapter(historyAdapter);

        btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() &&
                cm.getPrimaryClip().getItemCount() > 0) {
                String txt = cm.getPrimaryClip().getItemAt(0)
                    .getText().toString().trim();
                urlInput.setText(txt);
                status.setText("📋 Lien collé !");
            }
        });

        btnOpen.setVisibility(android.view.View.GONE);
        btnOpen.setOnClickListener(v -> {
            if (lastDownloadedFile != null && lastDownloadedFile.exists()) {
                Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", lastDownloadedFile);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "video/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Ouvrir avec..."));
            }
        });

        btnDownload.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) { status.setText("⚠️ Colle un lien !"); return; }
            btnOpen.setVisibility(android.view.View.GONE);
            pb.setProgress(0);
            pb.setVisibility(android.view.View.VISIBLE);
            status.setText("⏳ Démarrage...");
            btnDownload.setEnabled(false);
            new Thread(() -> {
                if (url.contains(".m3u8")) downloadHLS(url, status, btnDownload, btnOpen, pb);
                else                       downloadDirect(url, status, btnDownload, btnOpen, pb);
            }).start();
        });
    }

    /* ── Téléchargement direct (mp4, mkv…) ── */
    private void downloadDirect(String urlStr, TextView status, Button btn,
                                Button btnOpen, ProgressBar pb) {
        try {
            String ext = getExtension(urlStr);
            String fileName = "video_" + timestamp() + "." + ext;
            File out = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), fileName);

            HttpURLConnection conn = openConnection(urlStr);
            int total = conn.getContentLength();
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int len, dl = 0;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len); dl += len;
                    if (total > 0) {
                        int pct = (int)(dl * 100L / total);
                        runOnUiThread(() -> { pb.setProgress(pct);
                            status.setText("⏳ " + pct + "%"); });
                    }
                }
            }
            finishOk(out, fileName, status, btn, btnOpen, pb);
        } catch (Exception e) { finishErr(e.getMessage(), status, btn); }
    }

    /* ── Téléchargement HLS ── */
    private void downloadHLS(String m3u8Url, TextView status, Button btn,
                             Button btnOpen, ProgressBar pb) {
        try {
            runOnUiThread(() -> status.setText("⏳ Lecture m3u8..."));

            // 1. Parser la playlist
            List<String> segments = parseM3U8(m3u8Url);
            if (segments.isEmpty()) {
                finishErr("Aucun segment trouvé", status, btn); return;
            }

            // 2. Afficher le 1er segment pour debug
            String firstSeg = segments.get(0);
            runOnUiThread(() -> status.setText(
                "🔍 " + segments.size() + " segments\n1er: " + firstSeg.substring(
                    Math.max(0, firstSeg.length()-60))));

            // Pause 2s pour lire le debug
            Thread.sleep(2000);

            String fileName = "video_" + timestamp() + ".ts";
            File out = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), fileName);

            String baseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);
            byte[] buf = new byte[16384];
            long totalBytes = 0;
            int count = 0, total = segments.size();
            int failCount = 0;

            try (FileOutputStream fos = new FileOutputStream(out)) {
                for (String seg : segments) {
                    String segUrl = resolveUrl(seg, baseUrl, m3u8Url);
                    try {
                        HttpURLConnection conn = openConnection(segUrl);
                        int code = conn.getResponseCode();
                        if (code == 200) {
                            try (InputStream is = conn.getInputStream()) {
                                int len;
                                while ((len = is.read(buf)) > 0) {
                                    fos.write(buf, 0, len);
                                    totalBytes += len;
                                }
                            }
                        } else {
                            failCount++;
                        }
                        conn.disconnect();
                    } catch (Exception segEx) {
                        failCount++;
                    }
                    count++;
                    final int pct = (int)(count * 100.0 / total);
                    final long mb = totalBytes / (1024*1024);
                    final int fc = failCount;
                    runOnUiThread(() -> {
                        pb.setProgress(pct);
                        status.setText("⏳ " + count + "/" + total +
                            " (" + pct + "%) — " + mb + " MB" +
                            (fc > 0 ? " — ⚠️ " + fc + " échecs" : ""));
                    });
                }
            }

            if (totalBytes < 10240) {
                out.delete();
                // Afficher le 1er URL de segment pour diagnostic
                String dbg = resolveUrl(segments.get(0), baseUrl, m3u8Url);
                finishErr("Segments vides (0 bytes).\nURL seg: " + dbg, status, btn);
                return;
            }

            finishOk(out, fileName, status, btn, btnOpen, pb);

        } catch (Exception e) { finishErr(e.getMessage(), status, btn); }
    }

    /* ── Parser m3u8 ── */
    private List<String> parseM3U8(String url) throws Exception {
        List<String> segments = new ArrayList<>();
        String bestSubUrl = null;
        long bestBandwidth = 0;

        HttpURLConnection conn = openConnection(url);
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String l;
            while ((l = br.readLine()) != null) lines.add(l.trim());
        }
        conn.disconnect();

        String base = url.substring(0, url.lastIndexOf('/') + 1);
        boolean nextSeg = false;

        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isEmpty()) continue;

            // Playlist maître : prendre la qualité la plus haute
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                long bw = 0;
                if (l.contains("BANDWIDTH=")) {
                    try {
                        String bwStr = l.split("BANDWIDTH=")[1].split("[,\\s]")[0];
                        bw = Long.parseLong(bwStr);
                    } catch (Exception ignored) {}
                }
                if (i + 1 < lines.size()) {
                    String subLine = lines.get(i + 1).trim();
                    if (!subLine.startsWith("#") && bw >= bestBandwidth) {
                        bestBandwidth = bw;
                        bestSubUrl = subLine;
                    }
                }
                continue;
            }

            if (l.startsWith("#EXTINF")) { nextSeg = true; continue; }
            if (l.startsWith("#")) continue;

            if (nextSeg) {
                segments.add(l);
                nextSeg = false;
            }
        }

        // Playlist maître → récursion sur meilleure qualité
        if (segments.isEmpty() && bestSubUrl != null) {
            String subUrl = bestSubUrl.startsWith("http") ? bestSubUrl : base + bestSubUrl;
            return parseM3U8(subUrl);
        }

        return segments;
    }

    /* ── Helpers ── */
    private String resolveUrl(String seg, String baseUrl, String m3u8Url) {
        if (seg.startsWith("http://") || seg.startsWith("https://")) return seg;
        if (seg.startsWith("/")) {
            try {
                URL u = new URL(m3u8Url);
                return u.getProtocol() + "://" + u.getHost() + seg;
            } catch (Exception e) { return baseUrl + seg; }
        }
        return baseUrl + seg;
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "Chrome/124.0.0.0 Mobile Safari/537.36");
        c.setRequestProperty("Referer",  "https://uqload.is/");
        c.setRequestProperty("Origin",   "https://uqload.is");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private void finishOk(File f, String name, TextView status,
                          Button btn, Button btnOpen, ProgressBar pb) {
        lastDownloadedFile = f;
        addToHistory(name);
        long mb = f.length() / (1024 * 1024);
        runOnUiThread(() -> {
            btn.setEnabled(true);
            pb.setProgress(100);
            status.setText("✅ " + mb + " MB sauvegardés !\n" + name);
            btnOpen.setVisibility(android.view.View.VISIBLE);
        });
    }

    private void finishErr(String msg, TextView status, Button btn) {
        runOnUiThread(() -> {
            btn.setEnabled(true);
            status.setText("❌ " + msg);
        });
    }

    private String getExtension(String url) {
        for (String e : new String[]{"mp4","mkv","avi","webm","mov","flv"})
            if (url.contains("."+e)) return e;
        return "mp4";
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss",
            Locale.getDefault()).format(new Date());
    }

    private void addToHistory(String name) {
        runOnUiThread(() -> {
            history.add(0, "✅ " + name);
            if (history.size() > 20) history.remove(history.size()-1);
            historyAdapter.notifyDataSetChanged();
        });
    }
}
