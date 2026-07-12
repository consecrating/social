package com.example.instasaver;

import java.io.File;

/** A single downloaded media file on disk, plus derived metadata. */
public class DownloadedItem {

    public final File file;
    public final boolean isVideo;
    public final long lastModified;
    public final long size;
    /** Album (subfolder) name, or null when the item lives in the root folder. */
    public final String album;

    public DownloadedItem(File file, boolean isVideo, String album) {
        this.file = file;
        this.isVideo = isVideo;
        this.lastModified = file.lastModified();
        this.size = file.length();
        this.album = album;
    }

    public String getName() {
        return file.getName();
    }

    /** Filename without its extension (used as the default when renaming). */
    public String getBaseName() {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public String getExtension() {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot) : "";
    }

    public String getReadableSize() {
        long b = size;
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }
}
