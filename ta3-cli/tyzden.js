#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');
const { URL } = require('url');
const { sanitizeTitle } = require('./utils');

// ---------------------------------------------------------------------------
// Helper: HTTPS/HTTP GET returning full body as a string
// ---------------------------------------------------------------------------
function fetchText(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(url);
    const lib = parsedUrl.protocol === 'https:' ? https : http;

    const options = {
      hostname: parsedUrl.hostname,
      port: parsedUrl.port || (parsedUrl.protocol === 'https:' ? 443 : 80),
      path: parsedUrl.pathname + parsedUrl.search,
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ...headers,
      },
    };

    const req = lib.request(options, (res) => {
      // Follow redirects
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const redirectUrl = res.headers.location.startsWith('/')
          ? `${parsedUrl.origin}${res.headers.location}`
          : res.headers.location;
        return fetchText(redirectUrl, headers).then(resolve).catch(reject);
      }
      if (res.statusCode < 200 || res.statusCode >= 300) {
        return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
      }
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => resolve(data));
    });

    req.on('error', reject);
    req.end();
  });
}

// ---------------------------------------------------------------------------
// Helper: Download a binary file (with progress) via HTTP(S)
// ---------------------------------------------------------------------------
function downloadFile(url, destPath) {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(url);
    const lib = parsedUrl.protocol === 'https:' ? https : http;

    const options = {
      hostname: parsedUrl.hostname,
      port: parsedUrl.port || (parsedUrl.protocol === 'https:' ? 443 : 80),
      path: parsedUrl.pathname + parsedUrl.search,
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      },
    };

    const req = lib.request(options, (res) => {
      // Follow redirects
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const redirectUrl = res.headers.location.startsWith('/')
          ? `${parsedUrl.origin}${res.headers.location}`
          : res.headers.location;
        return downloadFile(redirectUrl, destPath).then(resolve).catch(reject);
      }
      if (res.statusCode < 200 || res.statusCode >= 300) {
        return reject(new Error(`HTTP ${res.statusCode} downloading ${url}`));
      }

      const totalBytes = parseInt(res.headers['content-length'] || '0', 10);
      let downloaded = 0;

      const out = fs.createWriteStream(destPath);

      res.on('data', (chunk) => {
        downloaded += chunk.length;
        out.write(chunk);
        if (totalBytes > 0) {
          const pct = ((downloaded / totalBytes) * 100).toFixed(1);
          const mb = (downloaded / 1024 / 1024).toFixed(2);
          const total = (totalBytes / 1024 / 1024).toFixed(2);
          process.stdout.write(`\r  Downloading... ${mb} / ${total} MB  (${pct}%)`);
        } else {
          const mb = (downloaded / 1024 / 1024).toFixed(2);
          process.stdout.write(`\r  Downloading... ${mb} MB`);
        }
      });

      res.on('end', () => {
        out.end();
      });

      out.on('finish', () => {
        process.stdout.write('\n');
        resolve(destPath);
      });

      res.on('error', (err) => {
        out.destroy();
        fs.unlink(destPath, () => {});
        reject(err);
      });
    });

    req.on('error', (err) => {
      fs.unlink(destPath, () => {});
      reject(err);
    });
    req.end();
  });
}



// ---------------------------------------------------------------------------
// Step 1: Fetch tyzden.sk article and extract Podbean player iframe src
// ---------------------------------------------------------------------------
async function extractPodbeanPlayerUrl(articleUrl) {
  console.log(`\nFetching article: ${articleUrl}`);
  const html = await fetchText(articleUrl);

  // Look for an iframe whose src points to podbean.com player-v2
  const iframeMatch = html.match(/src=["'](https:\/\/www\.podbean\.com\/player-v2\/[^"']+)["']/i);
  if (!iframeMatch) {
    throw new Error('Could not find Podbean player-v2 iframe in the article page.');
  }
  return iframeMatch[1];
}

// ---------------------------------------------------------------------------
// Step 2: Parse the player-v2 URL to get the episode ID, then build the
//         classic player API URL that returns JSON with the resource link.
// ---------------------------------------------------------------------------
function buildApiUrl(playerV2Url) {
  // e.g. https://www.podbean.com/player-v2/?i=e5m7w-1ae8cb5-pb&...
  const parsed = new URL(playerV2Url);
  const i = parsed.searchParams.get('i');
  if (!i) {
    throw new Error(`Could not extract episode ID from player URL: ${playerV2Url}`);
  }
  // The classic API endpoint
  return `https://www.podbean.com/player/${i}?scode=&pfauth=&referrer=&touchable=false&type=classic`;
}

// ---------------------------------------------------------------------------
// Step 3: Fetch the Podbean classic player JSON and extract episode data
// ---------------------------------------------------------------------------
async function fetchEpisodeData(apiUrl) {
  console.log(`Fetching Podbean API: ${apiUrl}`);
  const text = await fetchText(apiUrl, {
    Referer: 'https://www.podbean.com/',
  });

  let data;
  try {
    data = JSON.parse(text);
  } catch (e) {
    throw new Error(`Failed to parse Podbean API response as JSON: ${e.message}`);
  }

  const episodes = data.episodes;
  if (!episodes || episodes.length === 0) {
    throw new Error('No episodes found in Podbean API response.');
  }

  const ep = episodes[0];
  const resource = ep.resource || ep.downloadLink || ep.fallbackResource;
  if (!resource) {
    throw new Error('No resource URL found in episode data.');
  }

  return {
    title: ep.title || 'episode',
    resource,
    publishTime: ep.publishTime || '',
  };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
async function main() {
  const articleUrl = process.argv[2];
  if (!articleUrl) {
    console.error('Usage: node tyzden.js <tyzden.sk-article-url>');
    console.error('');
    console.error('Example:');
    console.error('  node tyzden.js "https://www.tyzden.sk/spolocnost/136086/bezpecnostny-radar-..."');
    process.exit(1);
  }

  try {
    // 1. Extract Podbean iframe src from the article
    const playerV2Url = await extractPodbeanPlayerUrl(articleUrl);
    console.log(`  Found player URL: ${playerV2Url}`);

    // 2. Build classic API URL from the episode ID in the player URL
    const apiUrl = buildApiUrl(playerV2Url);

    // 3. Fetch episode metadata (title + MP3 resource URL)
    const { title, resource, publishTime } = await fetchEpisodeData(apiUrl);
    console.log(`  Title:       ${title}`);
    console.log(`  Published:   ${publishTime}`);
    console.log(`  Resource:    ${resource}`);

    // 4. Parse publishTime (e.g. "June 12, 2026") into YYYY/MM/DD
    //    Fall back to today if parsing fails.
    let datePath = '';
    if (publishTime) {
      const parsed = new Date(publishTime);
      if (!isNaN(parsed.getTime())) {
        const yyyy = parsed.getFullYear();
        const mm = String(parsed.getMonth() + 1).padStart(2, '0');
        const dd = String(parsed.getDate()).padStart(2, '0');
        datePath = path.join(String(yyyy), mm, dd);
      }
    }
    if (!datePath) {
      const now = new Date();
      const yyyy = now.getFullYear();
      const mm = String(now.getMonth() + 1).padStart(2, '0');
      const dd = String(now.getDate()).padStart(2, '0');
      datePath = path.join(String(yyyy), mm, dd);
    }

    // 5. Build output path:  downloads/tyzden.sk/YYYY/MM/DD/<sanitized-title>.mp3
    const outDir = path.join(__dirname, 'downloads', 'tyzden.sk', datePath);
    if (!fs.existsSync(outDir)) {
      fs.mkdirSync(outDir, { recursive: true });
    }

    const filename = sanitizeTitle(title) + '.mp3';
    const destPath = path.join(outDir, filename);

    if (fs.existsSync(destPath)) {
      console.log(`\nFile already exists, skipping download:\n  ${destPath}`);
      return;
    }

    console.log(`\nSaving to: ${destPath}`);

    // 5. Download the MP3
    await downloadFile(resource, destPath);

    console.log(`\nDone! Saved to:\n  ${destPath}`);
    process.exit(0);
  } catch (err) {
    console.error(`\nError: ${err.message}`);
    process.exit(1);
  }
}

main();
