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

For a given post URL the app fetches the public post page and reads the Open Graph
meta tags Instagram embeds in the page:

- `og:video` -> the reel/video `.mp4`
- `og:image` -> the photo `.jpg`

The direct media URL is then handed to Android's built-in `DownloadManager`.
All extraction logic is isolated in
[`InstagramExtractor.java`](app/src/main/java/com/example/instasaver/InstagramExtractor.java)
so it is easy to update if Instagram changes its page markup.

## Limitations

- **Public content only.** Private/login-gated posts do not expose the media tags.
- **Carousels** (multi-item posts) currently download the first item only.
- Instagram changes its markup periodically; if extraction breaks, update the
  parser in `InstagramExtractor.java`.
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
