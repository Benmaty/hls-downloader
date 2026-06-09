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

        EditText urlInput = findViewById(R.id.urlInput);
        Button btnDownload = findViewById(R.id.btnDownload);
        Button btnPaste = findViewById(R.id.btnPaste);
        Button btnOpen = findViewById(R.id.btnOpen);
        TextView status = findViewById(R.id.status);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        ListView historyList = findViewById(R.id.historyList);

        historyAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, history);
        historyList.setAdapter(historyAdapter);

        btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                urlInput.setText(cm.getPrimaryClip().getItemAt(0).getText().toString().trim());
                status.setText("📋 Lien collé !");
            }
        });

        btnOpen.setVisibility(android.view.View.GONE);
        btnOpen.setOnClickListener(v -> {
            if (lastDownloadedFile != null && lastDownloadedFile.exists()) {
                Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", lastDownloadedFile);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "video/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "Ouvrir avec..."));
            }
        });

        btnDownload.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) { status.setText("⚠️ Colle un lien !"); return; }
            btnOpen.setVisibility(android.view.View.GONE);
            progressBar.setProgress(0);
            progressBar.setVisibility(android.view.View.VISIBLE);
            status.setText("⏳ Démarrage...");
            btnDownload.setEnabled(false);

            new Thread(() -> {
                if (url.contains(".m3u8")) {
                    downloadHLS(url, status, btnDownload, btnOpen, progressBar);
                } else {
                    downloadDirect(url, status, btnDownload, btnOpen, progressBar);
                }
            }).start();
        });
    }

    private void downloadDirect(String urlStr, TextView status, Button btn,
                                  Button btnOpen, ProgressBar pb) {
        try {
            String ext = getExtension(urlStr);
            String fileName = "video_" + timestamp() + "." + ext;
            File outFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), fileName);

            HttpURLConnection conn = openConnection(urlStr);
            int total = conn.getContentLength();
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int len, downloaded = 0;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                    downloaded += len;
                    if (total > 0) {
                        int pct = (int)(downloaded * 100L / total);
                        runOnUiThread(() -> {
                            pb.setProgress(pct);
                            status.setText("⏳ " + pct + "%");
                        });
                    }
                }
            }
            lastDownloadedFile = outFile;
            addToHistory(fileName);
            runOnUiThread(() -> {
                btn.setEnabled(true);
                pb.setProgress(100);
                status.setText("✅ Sauvegardé !\n" + fileName);
                btnOpen.setVisibility(android.view.View.VISIBLE);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                btn.setEnabled(true);
                status.setText("❌ Erreur : " + e.getMessage());
            });
        }
    }

    private void downloadHLS(String m3u8Url, TextView status, Button btn,
                               Button btnOpen, ProgressBar pb) {
        try {
            String fileName = "video_" + timestamp() + ".mp4";
            File outFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), fileName);

            List<String> segments = parseM3U8(m3u8Url);
            if (segments.isEmpty()) {
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    status.setText("❌ Aucun segment trouvé");
                });
                return;
            }

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                String baseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);
                byte[] buf = new byte[8192];
                int count = 0, total = segments.size();

                for (String seg : segments) {
                    String segUrl = seg.startsWith("http") ? seg : baseUrl + seg;
                    HttpURLConnection conn = openConnection(segUrl);
                    try (InputStream is = conn.getInputStream()) {
                        int len;
                        while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                    conn.disconnect();
                    count++;
                    final int pct = (int)(count * 100.0 / total);
                    final int c = count;
                    runOnUiThread(() -> {
                        pb.setProgress(pct);
                        status.setText("⏳ " + c + "/" + total + " segments (" + pct + "%)");
                    });
                }
            }

            lastDownloadedFile = outFile;
            addToHistory(fileName);
            runOnUiThread(() -> {
                btn.setEnabled(true);
                pb.setProgress(100);
                status.setText("✅ Sauvegardé !\n" + fileName);
                btnOpen.setVisibility(android.view.View.VISIBLE);
            });

        } catch (Exception e) {
            runOnUiThread(() -> {
                btn.setEnabled(true);
                status.setText("❌ Erreur : " + e.getMessage());
            });
        }
    }

    private List<String> parseM3U8(String url) throws Exception {
        List<String> segments = new ArrayList<>();
        HttpURLConnection conn = openConnection(url);
        String lastSub = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    if (line.contains("URI=")) {
                        // Segment chiffré, on continue quand même
                    }
                    continue;
                }
                if (line.endsWith(".m3u8")) lastSub = line;
                else segments.add(line);
            }
        }
        conn.disconnect();
        if (segments.isEmpty() && lastSub != null) {
            String base = url.substring(0, url.lastIndexOf('/') + 1);
            return parseM3U8(lastSub.startsWith("http") ? lastSub : base + lastSub);
        }
        return segments;
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36");
        conn.setRequestProperty("Referer", "https://uqload.is/");
        conn.setRequestProperty("Origin", "https://uqload.is");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        return conn;
    }

    private String getExtension(String url) {
        if (url.contains(".mp4")) return "mp4";
        if (url.contains(".mkv")) return "mkv";
        if (url.contains(".avi")) return "avi";
        if (url.contains(".webm")) return "webm";
        if (url.contains(".mov")) return "mov";
        return "mp4";
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    }

    private void addToHistory(String name) {
        runOnUiThread(() -> {
            history.add(0, "✅ " + name);
            if (history.size() > 20) history.remove(history.size() - 1);
            historyAdapter.notifyDataSetChanged();
        });
    }
}
