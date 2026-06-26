const https = require('https');
const url = "https://rr6---sn-8vq54vox03-aigs.googlevideo.com/videoplayback?expire=1781841143&ei=l2g0aue4DdGz8uMP6Jyl6Aw&ip=2a0a%3Aef40%3A8da%3A9d01%3A9ca3%3Aa7b3%3A71eb%3A5cbd&id=o-AIaEUqBg4auP8p6s5m6tFsdIkpsncFgaPiarJM2xoDqv&itag=140&source=youtube&requiressl=yes&xpc=EgVo2aDSNQ%3D%3D&cps=742&met=1781819543%2C&mh=VD&mm=31%2C29&mn=sn-8vq54vox03-aigs%2Csn-aigzrnsz&ms=au%2Crdu&mv=m&mvi=6&pl=42&rms=au%2Cau&pcm2=yes&initcwndbps=3440000&bui=ARmQxEXI0ZvPBB4gGQXc_D3Ab68PC6uCuXycMlnYoSeJnrTAD8xyGYyibnOYtX1oZEKBUGPDX6xPkBFG&spc=SQ-umiW157T9yqPBBx-8eczNsM6KJQ-B06I4CjJsrbj6&vprv=1&svpuc=1&mime=audio%2Fmp4&rqh=1&gir=yes&clen=309288&dur=19.063&lmt=1767107011597808&mt=1781819116&fvip=1&keepalive=yes&fexp=51565115%2C51565681%2C51987687&c=ANDROID_VR&txp=4530534&sparams=expire%2Cei%2Cip%2Cid%2Citag%2Csource%2Crequiressl%2Cxpc%2Cpcm2%2Cbui%2Cspc%2Cvprv%2Csvpuc%2Cmime%2Crqh%2Cgir%2Cclen%2Cdur%2Clmt&sig=AHEqNM4wRgIhAPfTuWgSHjMLUCGV5BZ9IhtTY1fTKK05-iyLRXocwjjFAiEAly_-ZwCWBfQyMvsfgmLqFyni_9BvTNQjm-VsR7pA9GQ%3D&lsparams=cps%2Cmet%2Cmh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpl%2Crms%2Cinitcwndbps&lsig=APaTxxMwRQIhAPbODUpQwHHQpJYDyaK-tsb4QhfT0Z5s6DHtMC5RY5O6AiBFo_VbvjZUD61otmCVTHqNoCZmCe82YTpuOS6eAHl_dw%3D%3D";

const options = {
    method: 'HEAD',
    headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36'
    }
};

const req = https.request(url, options, (res) => {
    console.log("HEAD request sent without Range headers");
    console.log("Status:", res.statusCode);
    console.log("Content-Length:", res.headers['content-length']);
    console.log("Accept-Ranges:", res.headers['accept-ranges']);
});
req.on('error', console.error);
req.end();

const optionsGet = {
    method: 'GET',
    headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36'
    }
};
const reqGet = https.request(url, optionsGet, (res) => {
    console.log("\nGET request sent without Range headers");
    console.log("Status:", res.statusCode);
    console.log("Content-Length:", res.headers['content-length']);
});
reqGet.on('error', console.error);
reqGet.end();

