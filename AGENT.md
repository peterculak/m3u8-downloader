# Project: ta3-downloader — Agent Instructions

This project downloads podcast/radio/TV archive audio for offline listening on a phone.

## Core Rule: Audio Only

**Always download audio only — never video.**

- Use `ffmpeg -vn` to strip video from m3u8/HLS streams
- Save as `.m4a` (AAC, 192k) for streams downloaded via ffmpeg
- Save as `.mp3` only when the source is already an mp3 file (e.g. Podbean)
- Never produce `.mp4` or any video format

## Shared Utilities (`utils.js`)

All filename sanitization must go through `sanitizeTitle()` in [`ta3-cli/utils.js`](ta3-cli/utils.js).  
**Never duplicate this function** — always `require('./utils')`.

`sanitizeTitle` rules:
- Normalize and strip diacritics (á→a, š→s, etc.)
- Keep only alphanumeric, spaces, dashes, underscores
- Replace spaces/underscores with a single `-`
- Strip leading/trailing dots and dashes (avoids hidden files in macOS Finder)

## Folder Structure

All downloads go under `ta3-cli/downloads/` using this pattern:

```
downloads/<source-domain>/YYYY/MM/DD/<sanitized-title>.<ext>
```

Examples:
- `downloads/ta3/2026/06/14/Hlavne-spravy.m4a`
- `downloads/tyzden.sk/2026/06/12/Bezpecnostny-radar.mp3`
- `downloads/stvr.sk/2026/06/14/O-5-minut-12.m4a`

The date comes from the episode/clip metadata, not the current date. Fall back to today only if unavailable.

## Process Exit

Always call `process.exit(0)` on success and `process.exit(1)` on error.  
Node.js will hang after download otherwise due to open HTTPS keep-alive sockets.

## Skip if Already Downloaded

Before downloading, check if the output file already exists on disk and skip with a message if so.

## Adding a New Downloader

1. Create `ta3-cli/<sitename>.js`
2. Add a `just <sitename> url:` task in [`ta3-cli/Justfile`](ta3-cli/Justfile)
3. Import `sanitizeTitle` from `./utils`
4. Follow the folder structure convention above
5. Audio only, correct extension, `process.exit(0)` at the end

## Justfile Tasks

| Command | Description |
|---|---|
| `just ta3` | Download latest ta3.com shows |
| `just tyzden <url>` | Download a tyzden.sk podcast episode |
| `just stvr <url>` | Download an stvr.sk TV archive episode (audio only) |
| `just yt-audio <url>` | Download YouTube audio as m4a |
| `just m3u8-audio <url> <filename>` | Download any m3u8 stream as m4a |
