# Project Rules & Knowledge

## yt-dlp Video Download Issues

When encountering `HTTP Error 403: Forbidden` or other blocking issues with `yt-dlp` in this project (whether in `ta3-cli` or `samsung-tv/YoutubeDownloader`), be aware of the following known behaviors and fixes:

1. **Cookies trigger 403s:** YouTube actively throttles and blocks `yt-dlp` requests that use authenticated browser cookies (e.g. `--cookies-from-browser chrome`) for standard video downloads. 
   - **Fix:** If a video fails with a 403 while using cookies, try removing the `--cookies-from-browser` flag. When unauthenticated, `yt-dlp` bypasses blocks by successfully solving JS challenges using its `jsc:deno` solver.
   
2. **Stale Player Cache:** `yt-dlp` caches the YouTube JavaScript player in `~/.cache/yt-dlp`. If this cache becomes stale or if YouTube rotates players, you may get a 403.
   - **Fix:** Pass the `--no-cache-dir` flag (or run `yt-dlp --rm-cache-dir`) to force `yt-dlp` to fetch the freshest player directly from YouTube.

3. **Client Throttling:** YouTube heavily throttles the default `web` client used by `yt-dlp`.
   - **Fix:** If the `web` client is blocked, try passing `--extractor-args "youtube:player_client=android,web"` to use the Android client instead (Note: the Android client may not fully support cookies).

4. **Consistency:** Ensure that the `yt-dlp` commands used in `ta3-cli/Justfile` and `samsung-tv/YoutubeDownloader/server/server.js` stay perfectly synced so both downloaders share the same capabilities and fixes.
