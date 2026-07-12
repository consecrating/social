package com.example.instasaver;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-album cosmetic metadata: a custom cover image (content URI string) or a
 * cover color. Stored in SharedPreferences keyed by album name.
 */
public class AlbumMeta {

    private static final String PREFS = "instasaver_album_meta";
    private final SharedPreferences prefs;

    public AlbumMeta(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String coverImage(String album) {
        return prefs.getString("img_" + album, null);
    }

    public int coverColor(String album) {
        return prefs.getInt("color_" + album, 0);
    }

    public void setCoverImage(String album, String uri) {
        prefs.edit().putString("img_" + album, uri).remove("color_" + album).apply();
    }

    public void setCoverColor(String album, int color) {
        prefs.edit().putInt("color_" + album, color).remove("img_" + album).apply();
    }

    public void clear(String album) {
        prefs.edit().remove("img_" + album).remove("color_" + album).apply();
    }

    /** Carry cosmetic settings across an album rename. */
    public void rename(String from, String to) {
        String img = coverImage(from);
        int color = coverColor(from);
        SharedPreferences.Editor e = prefs.edit();
        e.remove("img_" + from).remove("color_" + from);
        if (img != null) e.putString("img_" + to, img);
        if (color != 0) e.putInt("color_" + to, color);
        e.apply();
    }
}
