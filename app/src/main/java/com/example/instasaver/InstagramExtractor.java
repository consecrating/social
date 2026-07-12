package com.example.instasaver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves direct media URLs (mp4 for reels/videos, jpg for photos) from a
 * public Instagram post or reel link.
 *
 * Background: Instagram no longer serves og:video/og:image meta tags to
 * logged-out requests — the post page redirects to a login wall. So this class
 * talks to Instagram's web API the same way the website's own JavaScript does:
 *
 *   1. Hit instagram.com once to obtain a `csrftoken` cookie.
 *   2. Call the GraphQL endpoint for the post, identified by its shortcode,
 *      sending the `X-IG-App-ID` header (the web app id) plus the CSRF token.
 *   3. Parse `xdt_shortcode_media` for video_url / display_url, including
 *      carousel children (edge_sidecar_to_children).
 *
 * If the API path fails, it falls back to the legacy og: tag scrape.
 *
 * NOTE: This only works for PUBLIC content and depends on Instagram's private
 * endpoints, which change periodically. All the fragile bits are isolated here.
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

    /** Public Instagram web app id — required to bypass the logged-out wall. */
    private static final String IG_APP_ID = "936619743392459";

    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * doc_id values for the "PolarisPostActionLoadPostQueryQuery" GraphQL query.
     * Instagram rotates these; we try each in turn. Update this list if all fail.
     */
    private static final String[] DOC_IDS = {
            "8845758582119845",
            "9310670392322965",
            "10015901848480474",
            "7950326061742207"
    };

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
     * Resolve the FIRST downloadable media item (kept for backward compatibility).
     * Blocking — must run off the main thread.
     */
    public static Media resolve(String rawUrl) throws IOException {
        List<Media> all = resolveAll(rawUrl);
        if (all.isEmpty()) {
            throw new IOException("Couldn't find downloadable media.");
        }
        return all.get(0);
    }

    /**
     * Resolve ALL media items in the post (handles carousels / multi-item posts).
     * Blocking — must run off the main thread.
     *
     * @throws IOException on network failure or if no media could be found.
     */
    public static List<Media> resolveAll(String rawUrl) throws IOException {
        String shortcode = extractShortcode(rawUrl);
        if (shortcode == null) {
            throw new IOException("That doesn't look like an Instagram post or reel link.");
        }

        IOException firstError = null;

        // 1) The web GraphQL API (what instagram.com's own JS uses).
        try {
            List<Media> r = resolveViaApi(shortcode);
            if (!r.isEmpty()) return r;
        } catch (IOException e) {
            firstError = e;
        }

        // 2) The private mobile-web media info endpoint (different shape, often
        //    succeeds when GraphQL doc_ids are stale). Needs the numeric media id.
        try {
            List<Media> r = resolveViaMediaInfo(shortcode);
            if (!r.isEmpty()) return r;
        } catch (IOException e) {
            if (firstError == null) firstError = e;
        }

        // 3) Legacy Open Graph tag scrape (rarely works now, but cheap to try).
        try {
            List<Media> r = resolveViaOgTags(shortcode);
            if (!r.isEmpty()) return r;
        } catch (IOException ignored) {
            // fall through
        }

        throw new IOException(friendly(firstError != null ? firstError.getMessage() : null));
    }

    // ---------------------------------------------------------------------
    // Primary: Instagram web GraphQL API
    // ---------------------------------------------------------------------

    private static List<Media> resolveViaApi(String shortcode) throws IOException {
        String csrf = fetchCsrfToken();

        IOException last = null;
        for (String docId : DOC_IDS) {
            try {
                String json = graphqlQuery(shortcode, docId, csrf);
                List<Media> media = parseGraphql(json);
                if (!media.isEmpty()) {
                    return media;
                }
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        return new ArrayList<>();
    }

    /** Load instagram.com once and pull the csrftoken from Set-Cookie. */
    private static String fetchCsrfToken() {
        HttpURLConnection conn = null;
        try {
            conn = open("https://www.instagram.com/");
            conn.setInstanceFollowRedirects(true);
            conn.getResponseCode();

            Map<String, List<String>> headers = conn.getHeaderFields();
            List<String> cookies = headers.get("Set-Cookie");
            if (cookies == null) cookies = headers.get("set-cookie");
            if (cookies != null) {
                for (String c : cookies) {
                    if (c != null && c.startsWith("csrftoken=")) {
                        int end = c.indexOf(';');
                        return end > 0 ? c.substring("csrftoken=".length(), end)
                                : c.substring("csrftoken=".length());
                    }
                }
            }
        } catch (IOException ignored) {
            // no cookie — continue; some IPs still return media without it
        } finally {
            if (conn != null) conn.disconnect();
        }
        return "";
    }

    private static String graphqlQuery(String shortcode, String docId, String csrf)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open("https://www.instagram.com/graphql/query");
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-IG-App-ID", IG_APP_ID);
            conn.setRequestProperty("X-FB-Friendly-Name",
                    "PolarisPostActionLoadPostQueryQuery");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Referer",
                    "https://www.instagram.com/p/" + shortcode + "/");
            if (csrf != null && !csrf.isEmpty()) {
                conn.setRequestProperty("X-CSRFToken", csrf);
                conn.setRequestProperty("Cookie", "csrftoken=" + csrf);
            }

            String variables = "{\"shortcode\":\"" + shortcode + "\"}";
            String body = "av=0"
                    + "&doc_id=" + URLEncoder.encode(docId, "UTF-8")
                    + "&variables=" + URLEncoder.encode(variables, "UTF-8");

            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            return readBody(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<Media> parseGraphql(String json) throws IOException {
        List<Media> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return out;

            JSONObject media = data.optJSONObject("xdt_shortcode_media");
            if (media == null) media = data.optJSONObject("shortcode_media");
            if (media == null) return out;

            // Carousel? Iterate children.
            JSONObject sidecar = media.optJSONObject("edge_sidecar_to_children");
            if (sidecar != null) {
                JSONArray edges = sidecar.optJSONArray("edges");
                if (edges != null) {
                    for (int i = 0; i < edges.length(); i++) {
                        JSONObject node = edges.getJSONObject(i).optJSONObject("node");
                        if (node != null) addNode(node, out);
                    }
                    if (!out.isEmpty()) return out;
                }
            }

            addNode(media, out);
        } catch (Exception e) {
            throw new IOException("Unexpected response from Instagram.");
        }
        return out;
    }

    private static void addNode(JSONObject node, List<Media> out) {
        boolean isVideo = node.optBoolean("is_video", false);
        if (isVideo) {
            String videoUrl = node.optString("video_url", null);
            if (videoUrl != null && !videoUrl.isEmpty()) {
                out.add(new Media(videoUrl, true));
                return;
            }
        }
        String display = node.optString("display_url", null);
        if (display != null && !display.isEmpty()) {
            out.add(new Media(display, false));
        }
    }

    // ---------------------------------------------------------------------
    // Fallback: private mobile-web media info endpoint
    // ---------------------------------------------------------------------

    private static List<Media> resolveViaMediaInfo(String shortcode) throws IOException {
        String mediaId = shortcodeToMediaId(shortcode);
        if (mediaId == null) return new ArrayList<>();

        HttpURLConnection conn = null;
        try {
            conn = open("https://www.instagram.com/api/v1/media/" + mediaId + "/info/");
            conn.setRequestProperty("X-IG-App-ID", IG_APP_ID);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Referer",
                    "https://www.instagram.com/p/" + shortcode + "/");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            return parseMediaInfo(readBody(conn.getInputStream()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<Media> parseMediaInfo(String json) throws IOException {
        List<Media> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) return out;
            JSONObject item = items.getJSONObject(0);

            JSONArray carousel = item.optJSONArray("carousel_media");
            if (carousel != null) {
                for (int i = 0; i < carousel.length(); i++) {
                    addMediaInfoNode(carousel.getJSONObject(i), out);
                }
                if (!out.isEmpty()) return out;
            }
            addMediaInfoNode(item, out);
        } catch (Exception e) {
            throw new IOException("Unexpected response from Instagram.");
        }
        return out;
    }

    private static void addMediaInfoNode(JSONObject node, List<Media> out) {
        // video_versions -> pick the first (highest quality); else image_versions2.
        JSONArray videos = node.optJSONArray("video_versions");
        if (videos != null && videos.length() > 0) {
            String url = videos.optJSONObject(0).optString("url", null);
            if (url != null && !url.isEmpty()) {
                out.add(new Media(url, true));
                return;
            }
        }
        JSONObject iv2 = node.optJSONObject("image_versions2");
        if (iv2 != null) {
            JSONArray candidates = iv2.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                String url = candidates.optJSONObject(0).optString("url", null);
                if (url != null && !url.isEmpty()) {
                    out.add(new Media(url, false));
                }
            }
        }
    }

    /** Instagram shortcodes are base64 (custom alphabet) of the numeric media id. */
    static String shortcodeToMediaId(String shortcode) {
        if (shortcode == null || shortcode.isEmpty()) return null;
        final String alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        BigInteger base = BigInteger.valueOf(64);
        BigInteger id = BigInteger.ZERO;
        for (int i = 0; i < shortcode.length(); i++) {
            int val = alphabet.indexOf(shortcode.charAt(i));
            if (val < 0) return null; // unexpected char
            id = id.multiply(base).add(BigInteger.valueOf(val));
        }
        return id.toString();
    }

    // ---------------------------------------------------------------------
    // Fallback: legacy Open Graph tag scrape
    // ---------------------------------------------------------------------

    private static List<Media> resolveViaOgTags(String shortcode) throws IOException {
        List<Media> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = open("https://www.instagram.com/p/" + shortcode + "/");
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) {
                return out;
            }
            String html = readBody(conn.getInputStream());

            Matcher video = OG_VIDEO.matcher(html);
            if (video.find()) {
                out.add(new Media(unescape(video.group(1)), true));
                return out;
            }
            Matcher image = OG_IMAGE.matcher(html);
            if (image.find()) {
                out.add(new Media(unescape(image.group(1)), false));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static HttpURLConnection open(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", UA_MOBILE);
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    private static String readBody(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 3_000_000) break; // safety cap
            }
        }
        return sb.toString();
    }

    private static String friendly(String raw) {
        if (raw != null && (raw.contains("401") || raw.contains("403")
                || raw.toLowerCase().contains("wait"))) {
            return "Instagram temporarily blocked the request (rate limit). "
                    + "Wait a minute and try again, or switch between Wi-Fi and mobile data.";
        }
        return "Couldn't find downloadable media. The post may be private, "
                + "age-restricted, or login-only. If it's public, wait a minute "
                + "and try again (Instagram rate-limits repeated requests).";
    }

    /** Instagram HTML-escapes ampersands in URLs; undo the common cases. */
    private static String unescape(String s) {
        return s.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("\\u0026", "&");
    }
}
