#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const { URL } = require('url');
const { spawn } = require('child_process');
const { sanitizeTitle } = require('./utils');

// ---------------------------------------------------------------------------
// Helper: HTTPS GET returning full body as a string (follows redirects)
// ---------------------------------------------------------------------------
function fetchText(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(url);
    const options = {
      hostname: parsedUrl.hostname,
      port: parsedUrl.port || 443,
      path: parsedUrl.pathname + parsedUrl.search,
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        ...headers,
      },
    };

    const req = https.request(options, (res) => {
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
// Helper: Download audio-only from m3u8 via ffmpeg (strips video track)
// ---------------------------------------------------------------------------
function downloadAudio(m3u8Url, destPath) {
  return new Promise((resolve, reject) => {
    console.log('  Starting download...');

    const proc = spawn('ffmpeg', [
      '-y',
      '-loglevel', 'verbose',
      '-headers', 'Referer: https://www.stvr.sk/\r\n',
      '-i', m3u8Url,
      '-vn',          // drop video
      '-c:a', 'aac',
      '-b:a', '192k',
      destPath,
    ]);

    let buffer = '';
    let totalSeconds = 0;

    const handleData = (chunk) => {
      buffer += chunk.toString();
      const lines = buffer.split(/[\r\n]+/);
      buffer = lines.pop();
      for (const line of lines) {
        const dur = line.match(/Duration:\s*(\d{2}):(\d{2}):(\d{2})/i);
        if (dur) totalSeconds = parseInt(dur[1]) * 3600 + parseInt(dur[2]) * 60 + parseInt(dur[3]);
        const t = line.match(/time=(\d{2}):(\d{2}):(\d{2})/i);
        if (t) {
          const cur = parseInt(t[1]) * 3600 + parseInt(t[2]) * 60 + parseInt(t[3]);
          const pct = totalSeconds > 0 ? Math.min(100, (cur / totalSeconds) * 100).toFixed(1) : '?';
          const spd = (line.match(/speed=\s*([0-9.]+)x/i) || [])[1] || '';
          process.stdout.write(`\r  ${t[1]}:${t[2]}:${t[3]} / ${pct}%  ${spd}    `);
        }
      }
    };

    proc.stderr.on('data', handleData);
    proc.stdout.on('data', handleData);

    proc.on('close', (code) => {
      process.stdout.write('\n');
      if (code === 0) resolve(destPath);
      else reject(new Error(`ffmpeg exited with code ${code}`));
    });

    proc.on('error', reject);
  });
}

// ---------------------------------------------------------------------------
// Step 1: Extract archive ID from article or embed URL
// ---------------------------------------------------------------------------
function extractArchiveId(articleUrl) {
  // Matches both:
  //   /televizia/archiv/14036/602360
  //   /embed/archive/14036/602360
  const match = articleUrl.match(/\/(?:archiv|archive)\/\d+\/(\d+)/);
  if (!match) {
    throw new Error(`Could not extract archive ID from URL: ${articleUrl}`);
  }
  return match[1];
}

// ---------------------------------------------------------------------------
// Step 2: Fetch clip metadata from the JSON API
// ---------------------------------------------------------------------------
async function fetchClipData(archiveId) {
  const apiUrl = `https://www.stvr.sk/json/archive5f.json?id=${archiveId}&b=chrome&p=mac&f=0&d=1`;
  console.log(`Fetching STVR API: ${apiUrl}`);

  const text = await fetchText(apiUrl, { Referer: 'https://www.stvr.sk/' });
  let data;
  try {
    data = JSON.parse(text);
  } catch (e) {
    throw new Error(`Failed to parse STVR API JSON: ${e.message}`);
  }

  const clip = data.clip;
  if (!clip) throw new Error('No clip data in STVR API response.');

  // Prefer m3u8 (HLS) source
  const sources = clip.sources || [];
  const hls = sources.find(s => s.type === 'application/x-mpegurl' || (s.src || '').includes('.m3u8'));
  const src = hls ? hls.src : (sources[0] && sources[0].src);
  if (!src) throw new Error('No playable source found in clip.');

  return {
    title: clip.title || 'episode',
    datetime: clip.datetime_create || '',
    src,
  };
}

// ---------------------------------------------------------------------------
// Step 3: Parse "YYYY-MM-DD HH:MM" → "YYYY/MM/DD"
// ---------------------------------------------------------------------------
function parseDatePath(datetime) {
  if (datetime) {
    const match = datetime.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (match) return path.join(match[1], match[2], match[3]);
  }
  // Fall back to today
  const now = new Date();
  return path.join(
    String(now.getFullYear()),
    String(now.getMonth() + 1).padStart(2, '0'),
    String(now.getDate()).padStart(2, '0')
  );
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
async function main() {
  const articleUrl = process.argv[2];
  if (!articleUrl) {
    console.error('Usage: node stvr.js <stvr.sk-archive-url>');
    console.error('');
    console.error('Example:');
    console.error('  node stvr.js "https://www.stvr.sk/televizia/archiv/14036/602360"');
    process.exit(1);
  }

  try {
    // 1. Extract archive ID
    const archiveId = extractArchiveId(articleUrl);
    console.log(`\nArchive ID: ${archiveId}`);

    // 2. Fetch clip metadata
    const { title, datetime, src } = await fetchClipData(archiveId);
    console.log(`  Title:    ${title}`);
    console.log(`  Date:     ${datetime}`);
    console.log(`  Source:   ${src}`);

    // 3. Build output path: downloads/stvr.sk/YYYY/MM/DD/<title>.mp4
    const datePath = parseDatePath(datetime);
    const outDir = path.join(__dirname, 'downloads', 'stvr.sk', datePath);
    if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

    const filename = sanitizeTitle(title) + '.m4a';
    const destPath = path.join(outDir, filename);

    if (fs.existsSync(destPath)) {
      console.log(`\nFile already exists, skipping:\n  ${destPath}`);
      process.exit(0);
    }

    console.log(`\nSaving to: ${destPath}`);

    // 4. Download audio only via ffmpeg
    await downloadAudio(src, destPath);

    console.log(`\nDone! Saved to:\n  ${destPath}`);
    process.exit(0);
  } catch (err) {
    console.error(`\nError: ${err.message}`);
    process.exit(1);
  }
}

main();
