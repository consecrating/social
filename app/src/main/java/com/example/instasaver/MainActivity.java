package com.example.instasaver;

import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText urlInput;
    private Button downloadButton;
    private Button pasteButton;
    private ProgressBar progressBar;
    private TextView statusText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        downloadButton = findViewById(R.id.downloadButton);
        pasteButton = findViewById(R.id.pasteButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);

        downloadButton.setOnClickListener(v -> startDownloadFlow());
        pasteButton.setOnClickListener(v -> pasteFromClipboard());

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** Populate the field when a link is shared into the app from Instagram. */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(shared)) {
                urlInput.setText(shared.trim());
                startDownloadFlow();
            }
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (!TextUtils.isEmpty(text)) {
                urlInput.setText(text.toString().trim());
            }
        }
    }

    private void startDownloadFlow() {
        final String raw = urlInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            toast("Paste an Instagram link first.");
            return;
        }
        setBusy(true, "Resolving media…");

        executor.execute(() -> {
            try {
                final java.util.List<InstagramExtractor.Media> items =
                        InstagramExtractor.resolveAll(raw);
                final String shortcode = InstagramExtractor.extractShortcode(raw);
                main.post(() -> {
                    int index = 1;
                    for (InstagramExtractor.Media media : items) {
                        enqueueDownload(media, shortcode, index++);
                    }
                    int count = items.size();
                    setBusy(false, count == 1
                            ? "Downloading to your Downloads folder…"
                            : "Downloading " + count + " items to your Downloads folder…");
                });
            } catch (final Exception e) {
                main.post(() -> setBusy(false, "Error: " + e.getMessage()));
            }
        });
    }

    private void enqueueDownload(InstagramExtractor.Media media, String shortcode, int index) {
        try {
            String ext = media.isVideo ? ".mp4" : ".jpg";
            String base = "instasaver_"
                    + (shortcode != null ? shortcode : String.valueOf(System.currentTimeMillis()));
            String name = (index > 1 ? base + "_" + index : base) + ext;

            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(media.url));
            request.setTitle(name);
            request.setDescription("InstaSaver download");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, name);
            request.allowScanningByMediaScanner();

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                toast("Download started");
            } else {
                setBusy(false, "Download service unavailable on this device.");
            }
        } catch (Exception e) {
            setBusy(false, "Could not start download: " + e.getMessage());
        }
    }

    private void setBusy(boolean busy, String status) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        downloadButton.setEnabled(!busy);
        statusText.setText(status == null ? "" : status);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
