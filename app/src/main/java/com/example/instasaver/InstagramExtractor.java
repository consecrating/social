package com.example.instasaver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the direct media URL (mp4 for reels/videos, jpg for photos) from a
 * public Instagram post or reel link.
 *
 * Strategy: request the post's HTML with a desktop browser User-Agent and read
 * the Open Graph meta tags that Instagram embeds in the page head:
 *   <meta property="og:video" content="...mp4...">   -> video / reel
 *   <meta property="og:image" content="...jpg...">   -> photo (or video thumb)
 *
 * This only works for PUBLIC content. Private/login-gated posts will not expose
 * these tags. Instagram changes their markup periodically, so the parsing here
 * is intentionally isolated and easy to update.
 */
public class InstagramExtractor {

    public static class Media {
        public final String url;
        public final boolean isVideo;

        Media(String url, boolean isVideo) {
            this.url = url;
            this.isVideo = isVideo;
        }
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Matches instagram.com/p/<code>/, /reel/<code>/, /reels/<code>/, /tv/<code>/
    private static final Pattern SHORTCODE =
            Pattern.compile("instagram\\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)");

    private static final Pattern OG_VIDEO =
            Pattern.compile("<meta[^>]+property=\"og:video\"[^>]+content=\"([^\"]+)\"");
    private static final Pattern OG_IMAGE =
            Pattern.compile("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"");

    /** Extract the canonical shortcode, or null if the link isn't recognized. */
    public static String extractShortcode(String rawUrl) {
        if (rawUrl == null) return null;
        Matcher m = SHORTCODE.matcher(rawUrl);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Blocking network call — MUST be run off the main thread.
     * @throws IOException on network failure or if no media could be found.
     */
    public static Media resolve(String rawUrl) throws IOException {
        String shortcode = extractShortcode(rawUrl);
        if (shortcode == null) {
            throw new IOException("That doesn't look like an Instagram post or reel link.");
        }

        String canonical = "https://www.instagram.com/p/" + shortcode + "/";
        String html = fetch(canonical);

        // A reel is still reachable via /p/<code>/, so this single fetch covers all types.
        Matcher video = OG_VIDEO.matcher(html);
        if (video.find()) {
            return new Media(unescape(video.group(1)), true);
        }

        Matcher image = OG_IMAGE.matcher(html);
        if (image.find()) {
            return new Media(unescape(image.group(1)), false);
        }

        throw new IOException(
                "Couldn't find downloadable media. The post may be private or "
                        + "Instagram changed its page format.");
    }

    private static String fetch(String urlStr) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Instagram returned HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    // The meta tags live in <head>; stop once we've passed it to save memory.
                    if (sb.length() > 400000 && sb.indexOf("</head>") >= 0) {
                        break;
                    }
                }
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Instagram HTML-escapes ampersands in URLs; undo the common cases. */
    private static String unescape(String s) {
        return s.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("\\u0026", "&");
    }
}
