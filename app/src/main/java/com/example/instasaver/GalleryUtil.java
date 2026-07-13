package com.example.instasaver;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;

import java.util.ArrayList;
import java.util.List;

/** Helpers for picking media from the phone gallery and removing originals. */
public final class GalleryUtil {

    private GalleryUtil() { }

    public static final int DELETE_PENDING = -1; // a system confirm dialog was shown

    /**
     * Build a gallery pick intent. Uses ACTION_GET_CONTENT so the chooser lists
     * the user's Photos/Gallery apps (not just the Files document picker).
     */
    public static Intent pickIntent(boolean videoOnly, boolean imageOnly, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (videoOnly) {
            i.setType("video/*");
        } else if (imageOnly) {
            i.setType("image/*");
        } else {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        }
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    public static Intent chooser(boolean videoOnly, boolean imageOnly, boolean multiple) {
        return Intent.createChooser(pickIntent(videoOnly, imageOnly, multiple),
                "Select from gallery");
    }

    public static List<Uri> collectUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data == null) return uris;
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    /**
     * Remove the original gallery items after a successful import ("Move").
     *
     * On Android 11+ this shows the system's delete-confirmation dialog for
     * MediaStore items (the only reliable way to delete media the app didn't
     * create) via the supplied IntentSender launcher. Returns the number removed
     * immediately, or {@link #DELETE_PENDING} when a confirm dialog was launched.
     */
    public static int deleteOriginals(Activity activity, List<Uri> uris,
                                      ActivityResultLauncher<IntentSenderRequest> senderLauncher) {
        ContentResolver cr = activity.getContentResolver();
        int removed = 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ArrayList<Uri> mediaUris = new ArrayList<>();
            for (Uri u : uris) {
                if ("media".equals(u.getAuthority())) {
                    mediaUris.add(u);
                } else {
                    // Non-MediaStore (document) URIs: best-effort direct delete.
                    try {
                        if (DocumentsContract.isDocumentUri(activity, u)
                                && DocumentsContract.deleteDocument(cr, u)) {
                            removed++;
                        }
                    } catch (Exception ignored) { }
                }
            }
            if (!mediaUris.isEmpty()) {
                try {
                    PendingIntent pi = MediaStore.createDeleteRequest(cr, mediaUris);
                    VaultLock.beginInternalActivity(); // don't lock while the system dialog shows
                    senderLauncher.launch(
                            new IntentSenderRequest.Builder(pi.getIntentSender()).build());
                    return DELETE_PENDING;
                } catch (Exception ignored) { }
            }
            return removed;
        }

        // Android 10 and below: try a direct delete (works with WRITE permission).
        for (Uri u : uris) {
            try {
                if (DocumentsContract.isDocumentUri(activity, u)) {
                    if (DocumentsContract.deleteDocument(cr, u)) removed++;
                } else if (cr.delete(u, null, null) > 0) {
                    removed++;
                }
            } catch (Exception ignored) { }
        }
        return removed;
    }
}
