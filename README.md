# InstaSaver

A small, standalone Android app for downloading **public** Instagram reels and photos.

> This is a clean-room app. It is **not** a modified Instagram client and does **not**
> ask you to log in to Instagram, so it never touches your account credentials.

## Download

Grab the ready-to-install APK: **[InstaSaver.apk](InstaSaver.apk)**
(On the file page, use the **Download** / raw button.)

Install:
1. Copy the APK to your Android phone.
2. Enable *Install unknown apps* for your file manager/browser.
3. Tap the APK and install.

## How to use

- Open **InstaSaver**, paste a public Instagram post/reel link, and tap **Download**, or
- In Instagram, tap **Share -> InstaSaver** to send the link straight to the app.

Videos/reels are saved as `.mp4` and photos as `.jpg` in your phone's **Downloads** folder.

## How it works

Instagram no longer serves Open Graph (`og:video`/`og:image`) tags to logged-out
requests — the post page now redirects to a login wall. So the app talks to
Instagram's web API the same way the website's own JavaScript does:

1. Load `instagram.com` once to obtain a `csrftoken` cookie.
2. Call the GraphQL endpoint for the post (by its shortcode) with the
   `X-IG-App-ID` header + CSRF token. This is what bypasses the login wall.
3. Parse `xdt_shortcode_media` for `video_url` / `display_url`, including
   carousel children.

If that fails it falls back to the legacy `og:` tag scrape. All extraction logic
is isolated in
[`InstagramExtractor.java`](app/src/main/java/com/example/instasaver/InstagramExtractor.java).
The direct media URL is then handed to Android's built-in `DownloadManager`.

## Limitations (please read)

- **Public content only.** Private/age-restricted/login-only posts won't work.
- **Instagram actively blocks scraping.** From datacenter/VPN IPs you'll often get
  a `401 "Please wait a few minutes"` rate-limit. On a normal phone (mobile/Wi-Fi
  IP) it works far more reliably. If you get a rate-limit, wait a minute or toggle
  between Wi-Fi and mobile data.
- **`doc_id` values rotate.** Instagram periodically changes the GraphQL `doc_id`s;
  if every request starts failing, update the `DOC_IDS` array in
  `InstagramExtractor.java`.
- **Carousels** (multi-item posts) are supported — all items download.
- The bundled APK is **debug-signed** (fine for personal use).

## Building from source

Requirements: Android SDK (platform 34, build-tools 34.0.0) and JDK 17.

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Responsible use

Only download media you own or have permission to use. Respect Instagram's Terms of
Service and the rights of content creators.
