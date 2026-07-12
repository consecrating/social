package com.example.instasaver;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owns everything to do with the on-disk library of downloaded media.
 *
 * Files live in the app's own external storage (no runtime permission needed):
 *   videos -> getExternalFilesDir(Environment.DIRECTORY_MOVIES)
 *   photos -> getExternalFilesDir(Environment.DIRECTORY_PICTURES)
 *
 * "Albums" are simply single-level subfolders inside those directories, so
 * organizing is just moving files between folders.
 */
public class MediaRepository {

    public enum Sort {
        NEWEST, OLDEST, NAME, LARGEST
    }

    /** Sentinel album filter meaning "show everything". */
    public static final String ALL = "All";

    /** Reserved collection folders (kept out of the normal library/album views). */
    public static final String FAV_DIR = "Favorites";
    public static final String TRASH_DIR = "Trash";

    public static boolean isReserved(String name) {
        return FAV_DIR.equals(name) || TRASH_DIR.equals(name);
    }

    private static final String[] VIDEO_EXT = {".mp4", ".mov", ".webm", ".mkv"};
    private static final String[] PHOTO_EXT = {".jpg", ".jpeg", ".png", ".webp", ".heic"};

    private final Context appContext;

    public MediaRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // Roots
    // ------------------------------------------------------------------

    public File root(boolean isVideo) {
        return appContext.getExternalFilesDir(
                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES);
    }

    /** Ensure the root (and optional album subfolder) exists; returns the target dir. */
    public File ensureDir(boolean isVideo, String album) {
        File root = root(isVideo);
        if (root != null && !root.exists()) root.mkdirs();
        if (root == null) return null;
        if (album == null || album.isEmpty() || ALL.equals(album)) return root;
        File dir = new File(root, sanitize(album));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    public List<DownloadedItem> list(boolean isVideo, Sort sort, String albumFilter) {
        List<DownloadedItem> items = new ArrayList<>();
        File root = root(isVideo);
        if (root == null || !root.exists()) return items;

        if (albumFilter == null || ALL.equals(albumFilter)) {
            collect(root, isVideo, null, items);
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File d : dirs) {
                    if (!isReserved(d.getName())) collect(d, isVideo, d.getName(), items);
                }
            }
        } else {
            File dir = new File(root, sanitize(albumFilter));
            collect(dir, isVideo, albumFilter, items);
        }

        sortItems(items, sort);
        return items;
    }

    /**
     * List a single reserved collection (Favorites or Trash) for one media type.
     */
    public List<DownloadedItem> listCollection(boolean isVideo, String reservedDir, Sort sort) {
        List<DownloadedItem> items = new ArrayList<>();
        File root = root(isVideo);
        if (root == null) return items;
        collect(new File(root, reservedDir), isVideo, null, items);
        sortItems(items, sort);
        return items;
    }

    /**
     * The Delete bin holds BOTH videos and photos, so gather both trash folders.
     */
    public List<DownloadedItem> listTrashBoth(Sort sort) {
        List<DownloadedItem> items = new ArrayList<>();
        File videoRoot = root(true);
        File photoRoot = root(false);
        if (videoRoot != null) collect(new File(videoRoot, TRASH_DIR), true, null, items);
        if (photoRoot != null) collect(new File(photoRoot, TRASH_DIR), false, null, items);
        sortItems(items, sort);
        return items;
    }

    private void collect(File dir, boolean isVideo, String album, List<DownloadedItem> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && matches(f.getName(), isVideo)) {
                out.add(new DownloadedItem(f, isVideo, album));
            }
        }
    }

    /** Distinct album (subfolder) names for the given media type. */
    public List<String> albums(boolean isVideo) {
        Set<String> names = new LinkedHashSet<>();
        File root = root(isVideo);
        if (root != null && root.exists()) {
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null) {
                Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File d : dirs) {
                    if (!isReserved(d.getName()) && containsMedia(d, isVideo)) {
                        names.add(d.getName());
                    }
                }
            }
        }
        return new ArrayList<>(names);
    }

    private boolean containsMedia(File dir, boolean isVideo) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isFile() && matches(f.getName(), isVideo)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    public boolean delete(DownloadedItem item) {
        return item.file.delete();
    }

    /** Rename keeping the original extension. Returns the new file or null on failure. */
    public File rename(DownloadedItem item, String newBaseName) {
        String clean = sanitize(newBaseName);
        if (clean.isEmpty()) return null;
        File target = new File(item.file.getParentFile(), clean + item.getExtension());
        if (target.equals(item.file)) return item.file;
        if (target.exists()) target = uniquify(target);
        return item.file.renameTo(target) ? target : null;
    }

    /** Move a file into an album subfolder ("All" moves it back to the root). */
    public File moveToAlbum(DownloadedItem item, String album) {
        File dir = ensureDir(item.isVideo, album);
        if (dir == null) return null;
        File target = new File(dir, item.file.getName());
        if (target.equals(item.file)) return item.file;
        if (target.exists()) target = uniquify(target);
        return item.file.renameTo(target) ? target : null;
    }

    /**
     * Export a copy into the phone's public gallery via MediaStore so it shows
     * up in Photos/Gallery apps. Returns true on success.
     */
    public boolean saveToGallery(DownloadedItem item) {
        ContentResolver resolver = appContext.getContentResolver();
        String mime = item.isVideo ? "video/mp4" : "image/jpeg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, item.file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);

        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String rel = (item.isVideo ? Environment.DIRECTORY_MOVIES
                    : Environment.DIRECTORY_PICTURES) + File.separator + "InstaSaver";
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            collection = item.isVideo
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            collection = item.isVideo
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }

        Uri uri = resolver.insert(collection, values);
        if (uri == null) return false;

        try (InputStream in = new FileInputStream(item.file);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) return false;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void sortItems(List<DownloadedItem> items, Sort sort) {
        Comparator<DownloadedItem> c;
        switch (sort) {
            case OLDEST:
                c = Comparator.comparingLong(i -> i.lastModified);
                break;
            case NAME:
                c = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
                break;
            case LARGEST:
                c = (a, b) -> Long.compare(b.size, a.size);
                break;
            case NEWEST:
            default:
                c = (a, b) -> Long.compare(b.lastModified, a.lastModified);
                break;
        }
        Collections.sort(items, c);
    }

    private boolean matches(String name, boolean isVideo) {
        String lower = name.toLowerCase(Locale.US);
        for (String ext : (isVideo ? VIDEO_EXT : PHOTO_EXT)) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private File uniquify(File f) {
        String parent = f.getParent();
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        File candidate;
        do {
            candidate = new File(parent, base + "_" + i + ext);
            i++;
        } while (candidate.exists());
        return candidate;
    }

    private String sanitize(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".fileprovider";
    }
}
