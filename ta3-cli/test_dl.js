const https = require('https');

function fetchText(url, headers = {}) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers }, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => resolve(data));
    }).on('error', reject);
  });
}

async function test() {
  const url = 'https://share.transistor.fm/e/32b9d14c';
  const embedHtml = await fetchText(url);
  
  const mp3Match = embedHtml.match(/(https?:\/\/[a-zA-Z0-9.\/\\_-]+\.mp3)/);
  if (mp3Match) {
      console.log(mp3Match[1].replace(/\\\//g, '/'));
  } else {
      console.log("Not found");
  }
}

test().catch(console.error);
