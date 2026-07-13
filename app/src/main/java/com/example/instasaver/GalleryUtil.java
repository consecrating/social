package com.example.instasaver;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.core.content.ContextCompat;

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
            // Resolve each picked URI to a real MediaStore URI so it can be deleted.
            ArrayList<Uri> mediaUris = new ArrayList<>();
            for (Uri u : uris) {
                Uri media = resolveDeletable(activity, u);
                if (media != null && !mediaUris.contains(media)) {
                    mediaUris.add(media);
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

        // Android 10 and below: direct delete (works with READ/WRITE permission).
        for (Uri u : uris) {
            try {
                Uri media = resolveDeletable(activity, u);
                if (media != null && cr.delete(media, null, null) > 0) {
                    removed++;
                } else if (DocumentsContract.isDocumentUri(activity, u)
                        && DocumentsContract.deleteDocument(cr, u)) {
                    removed++;
                }
            } catch (Exception ignored) { }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Read-media permission (needed to look items up in MediaStore)
    // ------------------------------------------------------------------

    public static boolean hasReadMedia(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static String[] readMediaPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    // ------------------------------------------------------------------
    // Resolving a picked URI to a deletable MediaStore URI
    // ------------------------------------------------------------------

    /** Try direct id resolution, then a MediaStore lookup by display name + size. */
    static Uri resolveDeletable(Context ctx, Uri picked) {
        Uri direct = toMediaStoreUri(ctx, picked);
        if (direct != null) return direct;
        return queryByNameSize(ctx, picked);
    }

    private static Uri queryByNameSize(Context ctx, Uri picked) {
        ContentResolver cr = ctx.getContentResolver();
        String name = null;
        long size = -1;
        try (Cursor c = cr.query(picked,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) { }
        if (name == null) return null;

        Uri found = queryCollection(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, size);
        if (found == null) {
            found = queryCollection(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, name, size);
        }
        return found;
    }

    private static Uri queryCollection(ContentResolver cr, Uri collection,
                                       String name, long size) {
        String sel;
        String[] args;
        if (size > 0) {
            sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                    + MediaStore.MediaColumns.SIZE + "=?";
            args = new String[]{name, String.valueOf(size)};
        } else {
            sel = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            args = new String[]{name};
        }
        try (Cursor c = cr.query(collection,
                new String[]{MediaStore.MediaColumns._ID}, sel, args, null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Convert a picked content URI into a MediaStore URI that can be deleted.
     * Handles direct media URIs and the media documents provider
     * (e.g. "image:1234" / "video:1234"). Returns null if it can't be resolved.
     */
    static Uri toMediaStoreUri(Context ctx, Uri uri) {
        if (uri == null) return null;
        if ("media".equals(uri.getAuthority())) return uri;
        try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null) {
                    int colon = docId.indexOf(':');
                    if (colon > 0) {
                        String type = docId.substring(0, colon);
                        long id = Long.parseLong(docId.substring(colon + 1));
                        Uri base;
                        if ("image".equalsIgnoreCase(type)) {
                            base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                        } else if ("video".equalsIgnoreCase(type)) {
                            base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                        } else {
                            return null;
                        }
                        return ContentUris.withAppendedId(base, id);
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
