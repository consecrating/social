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

## Features

- **Six tabs:** Download, Reels, Photos, **My Reels**, **My Photos**, and **Delete**.
  - **Reels / Photos** — your full downloaded library, split by type.
  - **My Reels / My Photos** — curated collections; move your favorites here.
  - **Delete** — a bin that holds both reels and photos you no longer want, so you
    can review before removing them for good.
- **Multi-select** — long-press any item to enter selection mode, then move or
  delete many items at once (with Select all / Cancel).
- **Built-in gallery** — thumbnail grid of everything, with a play badge on videos.
- **Organize your downloads:**
  - **Hidden Albums vault** — file items into custom albums that are kept **out of
    the main Reels/Photos view**. Open the vault by **long-pressing the "InstaSaver"
    title** on the home screen. Great for keeping some items private/tucked away.
  - **Sort** — Newest, Oldest, Name, or Largest.
  - **Rename**, **Move to album**, move to collections, or move to the Delete bin.
- **Built-in swipeable viewer** — tap any item to open a full-screen viewer, then
  swipe left/right through the next/previous items.
  - **Photos:** pinch and double-tap to zoom, drag to pan.
  - **Reels:** distraction-free — no counter/controls while playing. Double-tap to
    pause (freezes the frame); double-tap again while paused to step to the next
    frame; single-tap to resume (or reveal the close bar).
- **Handle-based filenames** — downloads are named `creatorhandle-shortcode.mp4`
  instead of a generic prefix.
- **Hidden Albums vault as folders** — long-press the "InstaSaver" title to open
  it. Each album is a folder with a customizable cover (pick an image from your
  gallery, or choose a color), and opening one shows separate **Reels / Photos**
  tabs. Renaming/deleting albums is available from a folder's menu.
- **Private "Move to album"** — because albums are private, the Move-to-album
  action must be **pressed and held for 5 seconds** to unlock.
- **Import from gallery into the vault** — tap the **+** in the vault to pick
  photos/videos from your phone gallery and copy them into a hidden album. Choose
  **Move** to also remove the originals from the gallery (best-effort; some items
  may need manual deletion), or **Copy** to keep them.
- **Share** one or many items to other apps.
- **Save to phone gallery** — export a copy into your system Photos/Gallery.
- **Carousels** — multi-item posts download every photo/video.

## How to use

- Open **InstaSaver**, paste a public Instagram post/reel link on the **Download**
  tab and tap **Download**, or in Instagram tap **Share -> InstaSaver**.
- Downloaded videos appear under **Reels**, photos under **Photos**.
- Tap an item to open it; long-press (or the ⋮ button) for actions: Open, Share,
  Save to gallery, Move to album, Rename, Delete.

### Where files are stored

To let the app manage its own library without asking for broad storage permissions,
downloads are saved to the app's **private external folder**
(`Android/data/com.example.instasaver/files/Movies` and `.../Pictures`). Use
**Save to phone gallery** on any item to also place a copy in your system Gallery.
Uninstalling the app removes its private library (but not items you exported).

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
- Downloads are stored in the app's private external folder; use **Save to phone
  gallery** to copy an item into your system Gallery.
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
