# TA3 CLI Downloader Documentation

A command-line tool written in Node.js to scrape, download, and optionally share radio/video episodes from TA3.

---

## Download History (`download_history.json`)

The `download_history.json` file serves as a local tracking database of all URLs that have been successfully processed.

### Purpose and Benefits

1. **Avoids Redundant Network Requests (Speed & Resource Saving)**
   - When scraping show archives, the script gathers all recent episode URLs.
   - If a URL is already recorded in `download_history.json`, it is skipped immediately.
   - This prevents the script from making nested HTTP requests to fetch the episode details page, resolve the VOD media IDs, and fetch the livebox source templates for already-downloaded episodes.

2. **Ensures "Download Once" Even After Local File Cleanup**
   - If you delete downloaded files locally to free up disk space or move them elsewhere (e.g. archiving), the script remembers not to download them again because the URLs persist in `download_history.json`.

3. **Auto-healing Sync**
   - If you manually delete entries from `download_history.json` but the corresponding `.m4a` files still exist in the download directory, the script is smart enough to detect the files on disk, automatically re-append the URL to the history file, and skip the download.

---

## Configuration (`config.json`)

The script is driven by a `config.json` configuration specifying the shows to track, download locations, history file path, and optional sharing properties:

- `historyFile`: Location of the history file.
- `maxConcurrentDownloads`: Number of simultaneous downloads (default is 4).
- `whatsAppContact` / `whatsAppPhone`: Target contact details for sharing files.
- `sites`: Dictionary of domains, show URLs, and specific folder structures.

---

## How to Clear History / Redownload

If you need to redownload an episode:
1. Locate the episode's URL in `download_history.json` and delete its line.
2. Delete the corresponding local `.m4a` file from the `downloads/` folder.
3. Run `just ta3`.
