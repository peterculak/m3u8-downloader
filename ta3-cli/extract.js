const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: 'new' });
  const page = await browser.newPage();

  page.on('request', request => {
    const url = request.url();
    if (url.includes('.m3u8') || url.includes('.mp4') || url.includes('json') || url.includes('api')) {
      console.log('API/VIDEO URL:', url);
    }
  });

  await page.goto('https://www.ta3.com/relacia/1048727/nase-f-16-budu-chranit-pobaltie-efektivita-penazi-na-zbrojenie-danko-skusa-ficovu-trpezlivost', { waitUntil: 'networkidle2' });
  
  console.log('Page loaded, attempting to click video...');
  try {
    await page.evaluate(() => {
      const videoFrame = document.querySelector('iframe, video, .detail_video, .video-header-embed');
      if (videoFrame) {
        videoFrame.click();
      }
      const playBtn = document.querySelector('.play-btn, [role="button"]');
      if (playBtn) playBtn.click();
    });
  } catch (e) {
    console.error(e);
  }

  await new Promise(r => setTimeout(r, 8000));

  await browser.close();
})();
