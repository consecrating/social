package com.example.instasaver;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
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

import java.util.ArrayDeque;
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
    /** Queue of MediaStore URIs still to delete on Android 10 (sequential consent). */
    private static final ArrayDeque<Uri> legacyQueue = new ArrayDeque<>();
    /** URIs we've already shown a consent dialog for (avoids re-asking in a loop). */
    private static final java.util.HashSet<String> legacyConsentAsked = new java.util.HashSet<>();

    public static boolean isLegacyDelete() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R;
    }

    public static void clearLegacyQueue() {
        legacyQueue.clear();
    }

    public static int deleteOriginals(Activity activity, List<Uri> uris,
                                      ActivityResultLauncher<IntentSenderRequest> senderLauncher) {
        ContentResolver cr = activity.getContentResolver();

        // Resolve each picked URI to a real MediaStore URI so it can be deleted.
        ArrayList<Uri> mediaUris = new ArrayList<>();
        for (Uri u : uris) {
            Uri media = resolveDeletable(activity, u);
            if (media != null && !mediaUris.contains(media)) {
                mediaUris.add(media);
            }
        }
        if (mediaUris.isEmpty()) return 0;

        // Android 11+ : one system dialog confirms deletion of all of them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(cr, mediaUris);
                VaultLock.beginInternalActivity(); // don't lock while the system dialog shows
                senderLauncher.launch(
                        new IntentSenderRequest.Builder(pi.getIntentSender()).build());
                return DELETE_PENDING;
            } catch (Exception ignored) {
                return 0;
            }
        }

        // Android 10 (Q): delete each; the OS throws a RecoverableSecurityException
        // asking the user to allow it. After they allow, we RETRY the same item.
        legacyQueue.clear();
        legacyConsentAsked.clear();
        legacyQueue.addAll(mediaUris);
        return pumpLegacyQueue(activity, senderLauncher) ? DELETE_PENDING : 0;
    }

    /**
     * Delete queued items until one needs user consent, then launch that consent
     * dialog. The head is retried (not skipped) after consent is granted; if it
     * still fails after we've already asked once, it is skipped to avoid a loop.
     * Returns true if a consent dialog was launched (deletion pending).
     */
    private static boolean pumpLegacyQueue(Activity activity,
                                           ActivityResultLauncher<IntentSenderRequest> sender) {
        ContentResolver cr = activity.getContentResolver();
        while (!legacyQueue.isEmpty()) {
            Uri head = legacyQueue.peekFirst();
            try {
                cr.delete(head, null, null);
                legacyQueue.pollFirst(); // deleted (or already gone) → next
            } catch (Exception e) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && e instanceof RecoverableSecurityException
                        && !legacyConsentAsked.contains(head.toString())) {
                    try {
                        IntentSender is = ((RecoverableSecurityException) e)
                                .getUserAction().getActionIntent().getIntentSender();
                        legacyConsentAsked.add(head.toString());
                        VaultLock.beginInternalActivity();
                        sender.launch(new IntentSenderRequest.Builder(is).build());
                        return true; // wait for consent; head stays in the queue
                    } catch (Exception ex) {
                        legacyQueue.pollFirst();
                    }
                } else {
                    legacyQueue.pollFirst(); // already asked or non-recoverable → skip
                }
            }
        }
        return false;
    }

    /**
     * Call after the Android 10 consent dialog returns OK: the head now has a
     * write grant, so retrying the delete succeeds. Returns true if another
     * consent dialog was launched for a subsequent item.
     */
    public static boolean continueLegacyAfterConsent(Activity activity,
                                                     ActivityResultLauncher<IntentSenderRequest> sender) {
        return pumpLegacyQueue(activity, sender);
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

        final String dn = MediaStore.MediaColumns.DISPLAY_NAME;
        final String sz = MediaStore.MediaColumns.SIZE;
        final Uri[] collections = {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        };

        // 1) Exact match on name + size.
        if (name != null && size > 0) {
            for (Uri col : collections) {
                Uri f = findFirst(cr, col, dn + "=? AND " + sz + "=?",
                        new String[]{name, String.valueOf(size)});
                if (f != null) return f;
            }
        }
        // 2) Match on name only (size can differ for re-encoded copies).
        if (name != null) {
            for (Uri col : collections) {
                Uri f = findFirst(cr, col, dn + "=?", new String[]{name});
                if (f != null) return f;
            }
        }
        // 3) A single item that matches the size exactly (last resort).
        if (size > 0) {
            for (Uri col : collections) {
                Uri f = findUnique(cr, col, sz + "=?", new String[]{String.valueOf(size)});
                if (f != null) return f;
            }
        }
        return null;
    }

    private static Uri findFirst(ContentResolver cr, Uri collection,
                                 String sel, String[] args) {
        try (Cursor c = cr.query(collection,
                new String[]{MediaStore.MediaColumns._ID}, sel, args, null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Return the item only if the query matches exactly one row (avoids guessing). */
    private static Uri findUnique(ContentResolver cr, Uri collection,
                                  String sel, String[] args) {
        try (Cursor c = cr.query(collection,
                new String[]{MediaStore.MediaColumns._ID}, sel, args, null)) {
            if (c != null && c.getCount() == 1 && c.moveToFirst()) {
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
