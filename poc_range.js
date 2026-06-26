const https = require('https');
const url = "https://rr5---sn-8vq54vox03-aigs.googlevideo.com/videoplayback?expire=1781842491&ei=2200apuBCcHVp-oPyNSBqA4&ip=2a0a%3Aef40%3A8da%3A9d01%3A9ca3%3Aa7b3%3A71eb%3A5cbd&id=o-AH1a5jeAzdHt_wgUpaA-BIE6OJjjV_ICMV513aWmGYTg&itag=140&source=youtube&requiressl=yes&xpc=EgVo2aDSNQ%3D%3D&cps=372&met=1781820891%2C&mh=ve&mm=31%2C29&mn=sn-8vq54vox03-aigs%2Csn-aigzrn7e&ms=au%2Crdu&mv=m&mvi=5&pcm2cms=yes&pl=42&rms=au%2Cau&initcwndbps=3418750&bui=ARmQxEXw_1PPFW92O3wffhEi5P5fQdnkQ6KFFBvIGX6ebreqOHfjnJtvMDG8Ha6fbBnyTCyXDDNHaZUZ&spc=SQ-umnOjz3pxaJdR9CURR24RMYcG2JCcuGQ3fHzEoUod&vprv=1&svpuc=1&mime=audio%2Fmp4&rqh=1&gir=yes&clen=53710490&dur=3318.711&lmt=1781525708183967&mt=1781820558&fvip=2&keepalive=yes&fexp=51565116%2C51565681%2C51987687&c=ANDROID_VR&txp=6308224&sparams=expire%2Cei%2Cip%2Cid%2Citag%2Csource%2Crequiressl%2Cxpc%2Cbui%2Cspc%2Cvprv%2Csvpuc%2Cmime%2Crqh%2Cgir%2Cclen%2Cdur%2Clmt&sig=AHEqNM4wRQIhAPi5SXGgv1Pykv1SmWuuw4xoFJFMxHvIPlXqhoKnTGl6AiAzWkIuBeBqySZ0AKxkHWGNAiiyvBroVJsKXua7gB7q7g%3D%3D&lsparams=cps%2Cmet%2Cmh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpcm2cms%2Cpl%2Crms%2Cinitcwndbps&lsig=APaTxxMwRQIhANRSEEHpi7-umUcJxxX6JL-s8gGhN6GGo1KoS4jtydUJAiAZcGs1J_zaJLFFNtLQZnKQ6ervn6d5tT3CvFv6FvsJlw%3D%3D";

async function test() {
    // Test 1: HEAD
    await new Promise(resolve => {
        https.request(url, { method: 'HEAD', headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' } }, res => {
            console.log("\nHEAD Request:");
            console.log("Status:", res.statusCode);
            console.log("Content-Length:", res.headers['content-length']);
            resolve();
        }).end();
    });

    // Test 2: GET with Range: bytes=0-2097151
    await new Promise(resolve => {
        https.request(url, { method: 'GET', headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36', 'Range': 'bytes=0-2097151' } }, res => {
            console.log("\nGET Request (Range: bytes=0-2097151):");
            console.log("Status:", res.statusCode);
            console.log("Content-Length:", res.headers['content-length']);
            res.resume(); // consume body
            res.on('end', resolve);
        }).end();
    });

    // Test 3: GET with Range: bytes=0-
    await new Promise(resolve => {
        https.request(url, { method: 'GET', headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36', 'Range': 'bytes=0-' } }, res => {
            console.log("\nGET Request (Range: bytes=0-):");
            console.log("Status:", res.statusCode);
            console.log("Content-Length:", res.headers['content-length']);
            res.destroy();
            resolve();
        }).end();
    });
}
test().catch(console.error);
