package com.benmaty.hlsdownloader;

import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText urlInput = findViewById(R.id.urlInput);
        Button btnDownload = findViewById(R.id.btnDownload);
        TextView status = findViewById(R.id.status);

        btnDownload.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) { status.setText("⚠️ Colle un lien !"); return; }
            status.setText("⏳ Démarrage...");
            btnDownload.setEnabled(false);
            new Thread(() -> downloadHLS(url, status, btnDownload)).start();
        });
    }

    private void downloadHLS(String m3u8Url, TextView status, Button btn) {
        try {
            String fileName = "video_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.getDefault()).format(new Date()) + ".mp4";
            File outFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), fileName);

            // Lire le fichier m3u8
            List<String> segments = parseM3U8(m3u8Url);
            if (segments.isEmpty()) {
                updateStatus(status, btn, "❌ Aucun segment trouvé dans le m3u8");
                return;
            }

            updateStatus(status, btn, "⏳ 0/" + segments.size() + " segments...");

            // Télécharger et assembler tous les segments
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                String baseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);
                byte[] buf = new byte[8192];
                int count = 0;

                for (String seg : segments) {
                    String segUrl = seg.startsWith("http") ? seg : baseUrl + seg;
                    HttpURLConnection conn = openConnection(segUrl);
                    try (InputStream is = conn.getInputStream()) {
                        int len;
                        while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                    conn.disconnect();
                    count++;
                    final int c = count;
                    final int total = segments.size();
                    runOnUiThread(() -> status.setText("⏳ " + c + "/" + total + " segments..."));
                }
            }

            runOnUiThread(() -> {
                btn.setEnabled(true);
                status.setText("✅ Sauvegardé dans Téléchargements !\n" + fileName);
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
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            String lastM3u8 = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#EXT-X-KEY") ) continue;
                if (line.startsWith("#")) {
                    // Détecter playlist maître
                    continue;
                }
                if (line.endsWith(".m3u8")) {
                    lastM3u8 = line;
                } else if (line.endsWith(".mp4") || line.contains(".ts?")) {
                    segments.add(line);
                }
            }
            // Si c'est une playlist maître, re-parser la sous-playlist
            if (segments.isEmpty() && lastM3u8 != null) {
                String base = url.substring(0, url.lastIndexOf('/') + 1);
                String subUrl = lastM3u8.startsWith("http") ? lastM3u8 : base + lastM3u8;
                return parseM3U8(subUrl);
            }
        }
        conn.disconnect();
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

    private void updateStatus(TextView status, Button btn, String msg) {
        runOnUiThread(() -> {
            btn.setEnabled(true);
            status.setText(msg);
        });
    }
}
