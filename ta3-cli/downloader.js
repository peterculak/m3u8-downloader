const fs = require('fs');
const path = require('path');
const https = require('https');
const { spawn } = require('child_process');
const { sanitizeTitle } = require('./utils');

// Helper to make HTTPS GET requests returning text content
function fetchText(url, headers = {}) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        // Handle redirect
        const redirectUrl = res.headers.location.startsWith('/') 
          ? new URL(url).origin + res.headers.location
          : res.headers.location;
        return fetchText(redirectUrl, headers).then(resolve).catch(reject);
      }
      if (res.statusCode < 200 || res.statusCode >= 300) {
        return reject(new Error(`Failed to load ${url}, status code: ${res.statusCode}`));
      }
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => resolve(data));
    }).on('error', reject);
  });
}

// Helper to get date string YYYY-MM-DD in Slovak timezone (Europe/Bratislava),
// matching the data-date attribute used by ta3.com
function getLocalDateString(date) {
  return date.toLocaleDateString('en-CA', { timeZone: 'Europe/Bratislava' });
}

// Global UI state
let downloadTasks = [];
let drawInterval;

function initDisplay(tasks) {
  downloadTasks = tasks;
  // Reserve space for progress bars (one line per task)
  for (let i = 0; i < downloadTasks.length; i++) {
    process.stdout.write('\n');
  }
  drawInterval = setInterval(draw, 150);
}

function stopDisplay() {
  clearInterval(drawInterval);
  draw(); // Final redraw
}

function draw() {
  const lineCount = downloadTasks.length;
  // Move cursor up by lineCount lines to overwrite them
  process.stdout.write(`\x1B[${lineCount}A`);

  for (let i = 0; i < lineCount; i++) {
    const task = downloadTasks[i];
    // Clear line and rewrite
    process.stdout.write('\x1B[2K\r');

    const barLength = 15;
    const filledLength = Math.round(barLength * (task.progress / 100));
    const emptyLength = barLength - filledLength;
    const bar = '█'.repeat(filledLength) + '░'.repeat(emptyLength);

    const displayName = `[${task.showName}] ${task.title}`;
    const displayNameShort = displayName.length > 40
      ? displayName.substring(0, 37) + '...'
      : displayName.padEnd(40);

    let progressIndicator = '';
    if (task.progress > 0 || task.status === 'Downloading') {
      progressIndicator = `[${bar}] ${Math.round(task.progress).toString().padStart(3)}%`;
    } else {
      progressIndicator = `[${task.currentTimeStr}]`.padEnd(22);
    }

    const speedStr = (task.speed || 'N/A').padStart(10);

    let statusText = task.status;
    if (task.status === 'Finished') {
      statusText = `\x1B[32m${task.status}\x1B[0m`;
    } else if (task.status === 'Failed' || task.status.startsWith('Error')) {
      statusText = `\x1B[31m${task.status}\x1B[0m`;
    } else if (task.status === 'Downloading') {
      statusText = `\x1B[34m${task.status}\x1B[0m`;
    } else if (task.status === 'Starting...' || task.status === 'Resolving...') {
      statusText = `\x1B[33m${task.status}\x1B[0m`;
    } else {
      statusText = `\x1B[90m${task.status}\x1B[0m`;
    }

    process.stdout.write(`${displayNameShort} ${progressIndicator} | ${speedStr} | ${statusText}\n`);
  }
}

// Helper to run just m3u8-audio with ffmpeg progress parsing
function runDownload(m3u8Url, targetPath, task) {
  return new Promise((resolve) => {
    const proc = spawn('just', ['m3u8-audio', m3u8Url, targetPath], { cwd: __dirname });

    let buffer = '';
    const handleData = (chunk) => {
      buffer += chunk.toString();
      const lines = buffer.split(/[\r\n]+/);
      buffer = lines.pop();

      for (const line of lines) {
        // Parse Duration (e.g., Duration: 00:26:21.15)
        const durationMatch = line.match(/Duration:\s*(\d{2}):(\d{2}):(\d{2})\.(\d{2})/i);
        if (durationMatch) {
          const h = parseInt(durationMatch[1], 10);
          const m = parseInt(durationMatch[2], 10);
          const s = parseInt(durationMatch[3], 10);
          task.totalSeconds = h * 3600 + m * 60 + s;
        }

        // Parse progress time (e.g., time=00:08:38.64)
        const progressMatch = line.match(/time=(\d{2}):(\d{2}):(\d{2})\.(\d{2})/i);
        if (progressMatch) {
          const h = parseInt(progressMatch[1], 10);
          const m = parseInt(progressMatch[2], 10);
          const s = parseInt(progressMatch[3], 10);
          const currentSeconds = h * 3600 + m * 60 + s;
          task.currentTimeStr = `${progressMatch[1]}:${progressMatch[2]}:${progressMatch[3]}`;
          if (task.totalSeconds > 0) {
            task.progress = Math.min(100, (currentSeconds / task.totalSeconds) * 100);
          }
          const speedMatch = line.match(/speed=\s*([0-9.]+)x/i);
          if (speedMatch) task.speed = `${speedMatch[1]}x`;
          task.status = 'Downloading';
        }
      }
    };

    // ffmpeg writes to stderr
    proc.stderr.on('data', handleData);
    proc.stdout.on('data', handleData);

    proc.on('close', (code) => { resolve(code); });
    proc.on('error', () => { resolve(-1); });
  });
}

async function downloadEpisode(item, task, history, saveHistory) {
  const { ep, show, defaultFolder } = item;

  task.status = 'Resolving...';
  task.progress = 0;
  task.speed = 'N/A';
  task.totalSeconds = 0;
  task.currentTimeStr = '00:00';

  try {
    // 1. Fetch detail page
    const detailHtml = await fetchText(ep.url, {
      'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    });

    // 2. Check for Transistor embed
    const transistorMatch = detailHtml.match(/src=["'](https:\/\/share\.transistor\.fm\/e\/[^"']+)["']/);
    let masterM3u8Url = null;
    let isMp3 = false;

    if (transistorMatch) {
      const embedHtml = await fetchText(transistorMatch[1]);
      const mp3Match = embedHtml.match(/(https?:\/\/[a-zA-Z0-9.\/\\_-]+\.mp3)/);
      if (mp3Match) {
        masterM3u8Url = mp3Match[1].replace(/\\\//g, '/');
        isMp3 = true;
      }
    }

    if (!masterM3u8Url) {
      const videoIdMatch = detailHtml.match(/"videoId"\s*:\s*"([^"]+)"/) || detailHtml.match(/videoId\s*:\s*'([^']+)'/);
      if (!videoIdMatch) {
        task.status = 'Error: No video ID';
        return;
      }
      const videoId = videoIdMatch[1];

      // 3. Fetch VOD source template
      const jsSource = await fetchText('https://embed.livebox.cz/ta3_v2/vod-source.js', {
        'Referer': ep.url,
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      });

      const srcMatch = jsSource.match(/"src"\s*:\s*"([^"]+)"/);
      if (!srcMatch) {
        task.status = 'Error: Livebox rejected';
        return;
      }

      masterM3u8Url = 'https:' + srcMatch[1].replace('{0}', videoId);
    }

    // 4. Parse master playlist to find the lowest-bandwidth variant (much less data to download)
    let downloadUrl = masterM3u8Url;
    if (!isMp3) {
      try {
      const masterPlaylist = await fetchText(masterM3u8Url, {
        'Referer': 'https://www.ta3.com/',
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      });

      // Find variant with lowest BANDWIDTH
      const variants = [];
      const lines = masterPlaylist.split('\n');
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (line.startsWith('#EXT-X-STREAM-INF:')) {
          const bwMatch = line.match(/BANDWIDTH=(\d+)/);
          const nextLine = lines[i + 1] ? lines[i + 1].trim() : '';
          if (bwMatch && nextLine && !nextLine.startsWith('#')) {
            variants.push({ bandwidth: parseInt(bwMatch[1], 10), uri: nextLine });
          }
        }
      }

      if (variants.length > 0) {
        variants.sort((a, b) => a.bandwidth - b.bandwidth);
        const lowestVariant = variants[0];
        // Resolve relative URLs
        if (lowestVariant.uri.startsWith('http')) {
          downloadUrl = lowestVariant.uri;
        } else {
          const base = masterM3u8Url.substring(0, masterM3u8Url.lastIndexOf('/') + 1);
          downloadUrl = base + lowestVariant.uri;
        }
      }
    } catch (_) {
      // Fall back to master URL if variant parsing fails
      downloadUrl = masterM3u8Url;
      }
    }

    // 5. Construct output file path
    const baseDownloadDir = show.downloadFolder
      ? (path.isAbsolute(show.downloadFolder) ? show.downloadFolder : path.join(__dirname, show.downloadFolder))
      : (path.isAbsolute(defaultFolder) ? defaultFolder : path.join(__dirname, defaultFolder));

    const [year, month, day] = ep.date.split('-');
    const downloadDir = path.join(baseDownloadDir, year, month, day);

    if (!fs.existsSync(downloadDir)) {
      fs.mkdirSync(downloadDir, { recursive: true });
    }

    const cleanTitle = sanitizeTitle(ep.title);
    const targetPath = path.join(downloadDir, cleanTitle);
    const fullPath = targetPath + '.m4a';

    task.status = 'Starting...';

    const exitCode = await runDownload(downloadUrl, targetPath, task);

    if (exitCode === 0) {
      task.status = 'Finished';
      task.progress = 100;
      history.push(ep.url);
      saveHistory();
      return fullPath;
    } else {
      task.status = `Error: Exit ${exitCode}`;
      return null;
    }

  } catch (err) {
    task.status = 'Failed';
    return null;
  }
}


async function main() {
  const configPath = path.join(__dirname, 'config.json');
  if (!fs.existsSync(configPath)) {
    console.error(`Config file not found at ${configPath}`);
    process.exit(1);
  }

  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  const historyPath = path.isAbsolute(config.historyFile)
    ? config.historyFile
    : path.join(__dirname, config.historyFile);

  let history = [];
  if (fs.existsSync(historyPath)) {
    try {
      history = JSON.parse(fs.readFileSync(historyPath, 'utf8'));
    } catch (e) {
      console.warn(`Warning: Could not parse history file, starting fresh. Error: ${e.message}`);
    }
  }

  const saveHistory = () => {
    fs.writeFileSync(historyPath, JSON.stringify(history, null, 2), 'utf8');
  };

  const fetchAll = process.argv.includes('--all') || process.argv.includes('-a');
  const shouldShare = process.argv.includes('--share') || process.argv.includes('-s');

  let numberOfDays = 1;
  let targetShowName = null;
  for (let i = 2; i < process.argv.length; i++) {
    const arg = process.argv[i];
    if (arg.startsWith('numberOfDays=')) {
      const val = parseInt(arg.split('=')[1], 10);
      if (!isNaN(val)) numberOfDays = val;
    } else if (arg.startsWith('--days=')) {
      const val = parseInt(arg.split('=')[1], 10);
      if (!isNaN(val)) numberOfDays = val;
    } else if (arg === '-d' || arg === '--days') {
      const val = parseInt(process.argv[i + 1], 10);
      if (!isNaN(val)) {
        numberOfDays = val;
        i++;
      }
    } else if (arg.startsWith('--name=')) {
      targetShowName = arg.split('=')[1];
    } else if (arg === '-n' || arg === '--name') {
      if (i + 1 < process.argv.length) {
        targetShowName = process.argv[i + 1];
        i++;
      }
    }
  }

  // Pre-calculate allowed dates based on numberOfDays
  const allowedDates = [];
  if (!fetchAll) {
    for (let d = 0; d < numberOfDays; d++) {
      const date = new Date(Date.now() - d * 24 * 60 * 60 * 1000);
      allowedDates.push(getLocalDateString(date));
    }
  }

  // 1. Gather all pending downloads across all shows
  const pendingQueue = [];
  const sites = config.sites || {};
  let historyUpdated = false;

  console.log('Scraping shows, please wait...');

  for (const [siteName, siteConfig] of Object.entries(sites)) {
    const defaultFolder = siteConfig.defaultDownloadFolder || 'downloads';
    const shows = siteConfig.shows || [];

    for (const show of shows) {
      if (targetShowName && show.name !== targetShowName) {
        continue;
      }

      try {
        const listingHtml = await fetchText(show.url, {
          'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        });

        // Regex to extract articles with date
        const articleRegex = /<article[^>]+data-date=["']([^"']+)["'][^>]*>([\s\S]*?)<\/article>/gi;
        let match;
        const episodes = [];

        while ((match = articleRegex.exec(listingHtml)) !== null) {
          const date = match[1];
          const content = match[2];

          // Extract link relative or absolute
          const urlMatch = content.match(/href=["'](\/(?:relacia|clanok|podcast)\/[^"']+)["']/i);
          if (!urlMatch) continue;
          const url = urlMatch[1].startsWith('http') 
            ? urlMatch[1] 
            : `https://www.ta3.com${urlMatch[1]}`;

          // Extract title
          let title = '';
          const titleMatch = content.match(/<h[2-4][^>]*>[\s\S]*?<a[^>]*>([\s\S]*?)<\/a>[\s\S]*?<\/h[2-4]>/i) 
                            || content.match(/<h[2-4][^>]*>([\s\S]*?)<\/h[2-4]>/i);
          if (titleMatch) {
            title = titleMatch[1].replace(/<[^>]+>/g, '').trim();
          } else {
            const altMatch = content.match(/alt=["']([^"']+)["']/i);
            title = altMatch ? altMatch[1].trim() : 'episode';
          }

          episodes.push({ date, url, title });
        }

        let filteredEpisodes = episodes;
        if (!fetchAll) {
          filteredEpisodes = episodes.filter(ep => allowedDates.includes(ep.date));
        }

        // Filter duplicates and prepare download items
        for (const ep of filteredEpisodes) {
          if (history.includes(ep.url)) {
            continue;
          }

          // Check if file already exists on disk (avoid downloading already-downloaded shows)
          const baseDownloadDir = show.downloadFolder 
            ? (path.isAbsolute(show.downloadFolder) ? show.downloadFolder : path.join(__dirname, show.downloadFolder))
            : (path.isAbsolute(defaultFolder) ? defaultFolder : path.join(__dirname, defaultFolder));
          
          const [year, month, day] = ep.date.split('-');
          const downloadDir = path.join(baseDownloadDir, year, month, day);
          
          const cleanTitle = sanitizeTitle(ep.title);
          const targetPath = path.join(downloadDir, cleanTitle);
          const fullPath = targetPath + '.m4a';

          if (fs.existsSync(fullPath)) {
            history.push(ep.url);
            historyUpdated = true;
            continue;
          }

          pendingQueue.push({ ep, show, defaultFolder });
        }

      } catch (err) {
        console.error(`Failed to process show listing at ${show.url}: ${err.message}`);
      }
    }
  }

  if (historyUpdated) {
    saveHistory();
  }

  // Process downloads in chronological order (oldest first)
  pendingQueue.reverse();

  if (pendingQueue.length === 0) {
    console.log('\nNo new episodes to download.');
    return;
  }

  // Populate downloadTasks list for display
  const tasks = pendingQueue.map(item => ({
    id: item.ep.url,
    title: item.ep.title,
    showName: item.show.name,
    date: item.ep.date,
    progress: 0,
    speed: 'N/A',
    status: 'Pending',
    totalSeconds: 0,
    currentTimeStr: '00:00'
  }));

  limit = config.maxConcurrentDownloads || 4;
  console.log(`\nFound ${pendingQueue.length} new episodes. Starting parallel downloads (limit: ${limit}):\n`);

  initDisplay(tasks);

  // 2. Parallel execution pool
  const filesToShare = [];
  const numWorkers = Math.min(limit, pendingQueue.length);
  const workers = [];
  for (let i = 0; i < numWorkers; i++) {
    workers.push((async () => {
      while (pendingQueue.length > 0) {
        const item = pendingQueue.shift();
        const task = tasks.find(t => t.id === item.ep.url);
        const downloadedPath = await downloadEpisode(item, task, history, saveHistory);
        if (downloadedPath) {
          filesToShare.push(downloadedPath);
        }
      }
    })());
  }

  await Promise.all(workers);
  stopDisplay();
  console.log('\nAll downloads completed.');

  // 3. Share files sequentially via WhatsApp if requested
  if (shouldShare && filesToShare.length > 0 && config.whatsAppContact) {
    console.log(`\nSharing ${filesToShare.length} file(s) via WhatsApp to "${config.whatsAppContact}"...`);
    for (const filePath of filesToShare) {
      try {
        console.log(`Sharing: ${path.basename(filePath)}`);
        await shareViaWhatsApp(filePath, config.whatsAppPhone);
        // Wait 4 seconds to let WhatsApp UI process the send operation and stabilize
        await new Promise(r => setTimeout(r, 4000));
      } catch (err) {
        console.error(`Failed to share ${path.basename(filePath)}: ${err.message}`);
      }
    }
  }
}

function shareViaWhatsApp(filePath, phoneNumber) {
  return new Promise((resolve, reject) => {
    const escapedPath = filePath.replace(/"/g, '\\"');

    const appleScript = `
-- Copy file to clipboard as a file reference
set thePath to POSIX file "${escapedPath}"
set the clipboard to thePath as «class furl»

-- Open chat directly via URL scheme (bypasses search bar entirely)
do shell script "open 'whatsapp://send?phone=${phoneNumber}'"
delay 3.0 -- Wait for WhatsApp to open the chat and focus the message input

tell application "System Events"
  tell process "WhatsApp"
    -- Paste the file
    keystroke "v" using {command down}
    delay 3.0
    
    -- Press Enter to send from the media preview screen
    key code 36
    delay 1.0
  end tell
end tell
`;

    const proc = spawn('osascript', []);
    let stderr = '';
    proc.stderr.on('data', (data) => {
      stderr += data.toString();
    });
    proc.stdin.write(appleScript);
    proc.stdin.end();

    proc.on('close', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`osascript exited with code ${code}. Error: ${stderr.trim()}`));
      }
    });

    proc.on('error', reject);
  });
}

main().catch(console.error);
