package com.example.instasaver;

import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The "Download" tab: paste a link, resolve media, and enqueue downloads. */
public class DownloadFragment extends Fragment {

    private EditText urlInput;
    private Button downloadButton;
    private ProgressBar progressBar;
    private TextView statusText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        urlInput = v.findViewById(R.id.urlInput);
        downloadButton = v.findViewById(R.id.downloadButton);
        Button pasteButton = v.findViewById(R.id.pasteButton);
        progressBar = v.findViewById(R.id.progressBar);
        statusText = v.findViewById(R.id.statusText);

        downloadButton.setOnClickListener(x -> startDownloadFlow());
        pasteButton.setOnClickListener(x -> pasteFromClipboard());

        // Observe URLs shared into the app from Instagram.
        SharedUrlViewModel vm =
                new ViewModelProvider(requireActivity()).get(SharedUrlViewModel.class);
        vm.getPendingUrl().observe(getViewLifecycleOwner(), url -> {
            if (!TextUtils.isEmpty(url)) {
                urlInput.setText(url);
                vm.clear();
                startDownloadFlow();
            }
        });
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(requireContext());
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
                final List<InstagramExtractor.Media> items = InstagramExtractor.resolveAll(raw);
                final String shortcode = InstagramExtractor.extractShortcode(raw);
                main.post(() -> {
                    if (!isAdded()) return;
                    int index = 1;
                    for (InstagramExtractor.Media media : items) {
                        enqueueDownload(media, shortcode, index++);
                    }
                    int count = items.size();
                    setBusy(false, count + (count == 1 ? " item" : " items")
                            + " downloading. Check the Reels / Photos tabs.");
                });
            } catch (final Exception e) {
                main.post(() -> {
                    if (isAdded()) setBusy(false, "Error: " + e.getMessage());
                });
            }
        });
    }

    private void enqueueDownload(InstagramExtractor.Media media, String shortcode, int index) {
        try {
            String ext = media.isVideo ? ".mp4" : ".jpg";
            String base = "instasaver_"
                    + (shortcode != null ? shortcode : String.valueOf(System.currentTimeMillis()));
            String name = (index > 1 ? base + "_" + index : base) + ext;

            // Save into the app's own external Movies/Pictures dir so the library
            // can manage the files without any storage permission.
            String dirType = media.isVideo
                    ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;

            DownloadManager.Request request =
                    new DownloadManager.Request(android.net.Uri.parse(media.url));
            request.setTitle(name);
            request.setDescription("InstaSaver download");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(requireContext(), dirType, name);

            DownloadManager dm = (DownloadManager)
                    requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
            } else {
                setBusy(false, "Download service unavailable on this device.");
            }
        } catch (Exception e) {
            setBusy(false, "Could not start download: " + e.getMessage());
        }
    }

    private void setBusy(boolean busy, String status) {
        if (progressBar != null) progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (downloadButton != null) downloadButton.setEnabled(!busy);
        if (statusText != null) statusText.setText(status == null ? "" : status);
    }

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
