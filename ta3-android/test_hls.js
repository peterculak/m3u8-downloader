const fs = require('fs');
const https = require('https');

function fetchText(url) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve(data));
    }).on('error', reject);
  });
}

async function test() {
  const jsSource = await fetchText('https://embed.livebox.cz/ta3_v2/vod-source.js');
  const srcMatch = jsSource.match(/"src"\s*:\s*"([^"]+)"/);
  
  // Use a known video ID
  const m3u8Url = 'https:' + srcMatch[1].replace('{0}', '6C46B12C-A1A6-46A2-9426-DE0708CC0199');
  console.log("Master:", m3u8Url);
  
  const masterTxt = await fetchText(m3u8Url);
  const baseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);
  
  const variantLine = masterTxt.split('\n').find(l => l.trim() && !l.startsWith('#'));
  const variantUrl = variantLine.startsWith('http') ? variantLine : baseUrl + variantLine;
  console.log("Variant:", variantUrl);
  
  const variantTxt = await fetchText(variantUrl);
  const vBaseUrl = variantUrl.substring(0, variantUrl.lastIndexOf('/') + 1);
  
  const segmentLines = variantTxt.split('\n').filter(l => l.trim() && !l.startsWith('#'));
  const firstSegUrl = segmentLines[0].startsWith('http') ? segmentLines[0] : vBaseUrl + segmentLines[0];
  console.log("First Seg:", firstSegUrl);
}
test();
