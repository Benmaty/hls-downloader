package com.benmaty.hlsdownloader;

import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private File ffmpegFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText urlInput = findViewById(R.id.urlInput);
        Button btnDownload = findViewById(R.id.btnDownload);
        TextView status = findViewById(R.id.status);

        // Copier ffmpeg dans getCacheDir() qui est exécutable
        ffmpegFile = new File(getCacheDir(), "ffmpeg");
        try {
            if (!ffmpegFile.exists()) {
                copyFfmpeg();
            }
            // Re-set executable au cas où
            Runtime.getRuntime().exec("chmod 755 " + ffmpegFile.getAbsolutePath()).waitFor();
        } catch (Exception e) {
            status.setText("❌ Init ffmpeg : " + e.getMessage());
        }

        btnDownload.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) { status.setText("⚠️ Colle un lien !"); return; }

            String fileName = "video_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.getDefault()).format(new Date()) + ".mp4";
            String outputPath = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS) + "/" + fileName;

            status.setText("⏳ Téléchargement...");
            btnDownload.setEnabled(false);

            new Thread(() -> {
                try {
                    // S'assurer que ffmpeg est exécutable
                    ffmpegFile.setExecutable(true, false);

                    ProcessBuilder pb = new ProcessBuilder(
                        ffmpegFile.getAbsolutePath(),
                        "-user_agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
                        "-headers",
                        "Referer: https://uqload.is/\r\nOrigin: https://uqload.is",
                        "-i", url,
                        "-c", "copy",
                        outputPath
                    );
                    pb.redirectErrorStream(true);
                    pb.environment().put("LD_LIBRARY_PATH", getCacheDir().getAbsolutePath());
                    Process p = pb.start();

                    StringBuilder log = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) log.append(line).append("\n");
                    }
                    int rc = p.waitFor();

                    runOnUiThread(() -> {
                        btnDownload.setEnabled(true);
                        if (rc == 0) status.setText("✅ Sauvegardé !\n" + fileName);
                        else status.setText("❌ Erreur :\n" +
                            log.substring(Math.max(0, log.length() - 400)));
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        btnDownload.setEnabled(true);
                        status.setText("❌ Exception : " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void copyFfmpeg() throws IOException {
        try (InputStream is = getAssets().open("ffmpeg");
             FileOutputStream fos = new FileOutputStream(ffmpegFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
        }
        ffmpegFile.setExecutable(true, false);
    }
}
