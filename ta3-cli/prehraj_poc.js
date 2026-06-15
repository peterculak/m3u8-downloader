#!/usr/bin/env node
/**
 * prehraj_poc.js  –  proof of concept for prehraj.to scraper + download
 *
 * Usage:
 *   node prehraj_poc.js              → search "spider man", download first result
 *   node prehraj_poc.js "avengers"  → search for something else
 */

const cheerio = require('cheerio');
const fs = require('fs');
const path = require('path');
const https = require('https');

const EMAIL    = 'fr0z3nk0@gmail.com';
const PASSWORD = 'hawkon-fybcab-1konQy';
const BASE     = 'https://prehraj.to';
const DOWNLOAD_DIR = path.join(__dirname, 'downloads', 'prehraj.to');

let sessionCookies = '';

// ─── helpers ─────────────────────────────────────────────────────────────────

async function get(url) {
  const r = await fetch(url, {
    headers: { Cookie: sessionCookies, 'User-Agent': 'Mozilla/5.0' }
  });
  if (!r.ok) throw new Error(`HTTP ${r.status} for ${url}`);
  return r.text();
}

function parseCookies(response) {
  const raw = response.headers.get('set-cookie') || '';
  return raw.split(/,(?=\s*[a-zA-Z0-9_]+=)/)
            .map(c => c.split(';')[0].trim())
            .filter(Boolean)
            .join('; ');
}

function sanitize(title) {
  return title.replace(/[/\\:*?"<>|]/g, '-').replace(/\s+/g, '-').slice(0, 120);
}

// ─── login ───────────────────────────────────────────────────────────────────

async function login() {
  console.log('▶  Logging in as', EMAIL);
  const body = new URLSearchParams({
    email: EMAIL, password: PASSWORD,
    _do: 'loginDialog-login-loginForm-submit',
    login: 'Přihlásit se'
  });
  const r = await fetch(`${BASE}/?frm=loginDialog-login-loginForm`, {
    method: 'POST', body,
    headers: { 'User-Agent': 'Mozilla/5.0', Referer: BASE + '/' },
    redirect: 'manual'
  });
  sessionCookies = parseCookies(r);
  if (!sessionCookies.includes('u_uid')) throw new Error('Login failed – no session cookie');
  console.log('✓  Login OK\n');
}

// ─── search ──────────────────────────────────────────────────────────────────

async function search(query) {
  const encoded = encodeURIComponent(query);
  const url = `${BASE}/hledej/${encoded}`;
  console.log('▶  Searching:', url);

  const html = await get(url);
  const $ = cheerio.load(html);
  const results = [];

  // Exact structure: div.video-wrapper > div > a.video--link
  $('.video-wrapper').each((_, wrapper) => {
    const link  = $(wrapper).find('a.video--link');
    const href  = link.attr('href') || '';
    const title = (link.attr('title') || $(wrapper).find('.video__title').text()).trim();
    const thumb = $(wrapper).find('img.thumb1').attr('src') || $(wrapper).find('img.thumb').first().attr('src') || '';

    if (!href || !title) return;
    results.push({ title, pageUrl: href.startsWith('http') ? href : `${BASE}${href}`, thumb });
  });

  console.log(`✓  Found ${results.length} results:`);
  results.slice(0, 5).forEach((r, i) =>
    console.log(`  [${i+1}] ${r.title}\n       ${r.pageUrl}`)
  );
  if (results.length > 5) console.log(`  ... and ${results.length - 5} more`);
  console.log();
  return results;
}

// ─── resolve video URL ────────────────────────────────────────────────────────

async function resolveVideoUrl(pageUrl) {
  console.log('▶  Resolving video URL from:', pageUrl);
  const html = await get(pageUrl);
  // Decode HTML entities first so &amp; doesn't break the URL
  const decoded = html.replace(/&amp;/g, '&');
  const match = decoded.match(/https:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*/);
  if (!match) throw new Error('No video URL found on page: ' + pageUrl);
  const videoUrl = match[0].replace(/\\u0026/g, '&').replace(/\\\//g, '/');
  console.log('✓  Video URL found:', videoUrl.slice(0, 80) + '...\n');
  return videoUrl;
}

// ─── download ────────────────────────────────────────────────────────────────

async function downloadVideo(videoUrl, title) {
  fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
  const filename = sanitize(title) + '.mp4';
  const destPath = path.join(DOWNLOAD_DIR, filename);

  console.log(`▶  Downloading: ${filename}`);
  console.log(`   Destination: ${destPath}`);

  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(destPath);
    let downloaded = 0;
    let total = 0;
    let lastPct = -1;

    function doRequest(url) {
      https.get(url, {
        headers: {
          Cookie: sessionCookies,
          'User-Agent': 'Mozilla/5.0',
          'Referer': BASE + '/'
        }
      }, res => {
        // Follow redirects
        if (res.statusCode === 301 || res.statusCode === 302) {
          return doRequest(res.headers.location);
        }
        if (res.statusCode !== 200) {
          return reject(new Error(`Download failed: HTTP ${res.statusCode}`));
        }

        total = parseInt(res.headers['content-length'] || '0', 10);
        if (total) {
          const mb = (total / 1024 / 1024).toFixed(1);
          console.log(`   Total size : ${mb} MB`);
        }

        res.on('data', chunk => {
          downloaded += chunk.length;
          if (total) {
            const pct = Math.floor((downloaded / total) * 100);
            if (pct !== lastPct && pct % 5 === 0) {
              const dlMb = (downloaded / 1024 / 1024).toFixed(1);
              process.stdout.write(`\r   Progress  : ${pct}% (${dlMb} MB)`);
              lastPct = pct;
            }
          }
        });

        res.pipe(file);
        file.on('finish', () => {
          file.close();
          const sizeMb = (fs.statSync(destPath).size / 1024 / 1024).toFixed(2);
          console.log(`\n✓  Download complete! File: ${destPath} (${sizeMb} MB)\n`);
          resolve(destPath);
        });
      }).on('error', err => {
        fs.unlink(destPath, () => {});
        reject(err);
      });
    }

    doRequest(videoUrl);
  });
}

// ─── main ─────────────────────────────────────────────────────────────────────

async function main() {
  const query = process.argv[2] || 'spider man';
  await login();
  const results = await search(query);
  if (results.length === 0) { console.log('No results found.'); return; }

  const first = results[0];
  console.log(`▶  Using result #1: "${first.title}"`);
  const videoUrl = await resolveVideoUrl(first.pageUrl);
  await downloadVideo(videoUrl, first.title);
}

main().catch(err => { console.error('\n✕  Error:', err.message); process.exit(1); });
