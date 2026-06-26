const ytdl = require('@distube/ytdl-core');
const https = require('https');

async function test() {
    const videoUrl = 'https://youtu.be/N4oRw8HmqGw';
    console.log("Fetching info for", videoUrl);
    
    const info = await ytdl.getInfo(videoUrl);
    const format = ytdl.chooseFormat(info.formats, { quality: 'highestaudio' });
    
    console.log("Selected format ITAG:", format.itag);
    console.log("Format has contentLength:", format.contentLength);
    
    const url = format.url;
    console.log("URL:", url.substring(0, 100) + "...");
    
    // 1. HEAD request
    await new Promise(resolve => {
        const req = https.request(url, { method: 'HEAD', headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' } }, res => {
            console.log("\nHEAD Request:");
            console.log("Status:", res.statusCode);
            console.log("Content-Length header:", res.headers['content-length']);
            resolve();
        });
        req.end();
    });
    
    // 2. GET request without Range
    await new Promise(resolve => {
        const req = https.request(url, { method: 'GET', headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' } }, res => {
            console.log("\nGET Request (No Range):");
            console.log("Status:", res.statusCode);
            console.log("Content-Length header:", res.headers['content-length']);
            req.destroy();
            resolve();
        });
        req.end();
    });
}
test().catch(console.error);
