package com.benmaty.hlsdownloader;

import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.FFmpeg;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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

            String fileName = "video_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".mp4";
            String outputPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + fileName;

            status.setText("⏳ Téléchargement...");
            btnDownload.setEnabled(false);

            new Thread(() -> {
                int rc = FFmpeg.execute(
                    "-user_agent \"Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36\" " +
                    "-headers \"Referer: https://uqload.is/\r\nOrigin: https://uqload.is\" " +
                    "-i \"" + url + "\" -c copy \"" + outputPath + "\""
                );
                runOnUiThread(() -> {
                    btnDownload.setEnabled(true);
                    if (rc == 0) status.setText("✅ Sauvegardé !\n" + fileName);
                    else status.setText("❌ Erreur code : " + rc);
                });
            }).start();
        });
    }
}
