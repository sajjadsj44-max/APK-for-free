# Transcripts (Android)

Share a TikTok, YouTube, Instagram or Facebook video to this app and get its transcript.
It works on the phone itself: captions are read when the video has them, otherwise the
speech is transcribed on the phone with Whisper (whisper.cpp). No PC or server needed.

## Get the APK

Every push to this repo builds the app automatically (about 10–20 minutes).

1. Open the repo's **Releases** page (or the **Actions** tab to watch the build).
2. Open the newest "Transcripts build N" and tap **Transcripts-N.apk** to download.
3. Install it. On Redmi/Xiaomi:
   - Allow **Install unknown apps** for your browser or file manager when asked.
   - If Play Protect warns, tap **More details → Install anyway**.
4. New builds install over the old one; downloaded speech models are kept.

## Use

- In TikTok or YouTube tap **Share → Transcripts**, or paste links in the app.
- **Source:** captions first (fast), then speech-to-text if there are none.
- **Accuracy:** Tiny is fastest, Base is the default, Small is most accurate but slow on a phone.
  Each model downloads once, on first use.
- **Translate to English** turns Urdu/Hindi speech into English text.
- Copy, Share, or Save as .txt / .srt. Search inside a transcript.
- Keep the app open while it works. Back keeps it running in the background, but
  Xiaomi may stop it: Settings → Apps → Transcripts → Battery saver → **No restrictions**.

## If something fails

| Problem | Fix |
|---|---|
| "Site blocked the request / changed" | Tap **Update downloader** at the bottom of the app |
| Speech-to-text very slow | Use **Tiny**, or **Captions only** for YouTube |
| App closes during long videos | Set battery saver to **No restrictions** |
| Build failed on GitHub | Open Actions → failed run → copy the red error lines and send them to Claude |

## Built with

- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp + FFmpeg for Android)
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.9.4
- Personal use only. Downloading may be against a platform's terms.
