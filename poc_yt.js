const fs = require('fs');
const html = require('child_process').execSync('curl -sL https://www.youtube.com/@BraňoZávodskýNaživo/streams', { maxBuffer: 1024 * 1024 * 10 }).toString();
const startMarker = "var ytInitialData = ";
const jsonStart = html.indexOf(startMarker) + startMarker.length;
const endIndex = html.indexOf(";</script>", jsonStart);
const obj = JSON.parse(html.substring(jsonStart, endIndex).trim());

const videos = [];
function parseRelativeDate(relativeStr) {
    if (!relativeStr) return "1970-01-01";
    let text = relativeStr.toLowerCase();
    let numMatch = text.match(/(\d+)/);
    let num = numMatch ? parseInt(numMatch[1]) : 1;
    
    let isToday = false;
    let days = 0;
    
    if (text.includes("day") || text.includes("deň") || text.includes("dňom") || text.includes("dňami") || text.includes("dní") || text.includes("dnem")) {
        days = -num;
    } else if (text.includes("week") || text.includes("týždeň") || text.includes("týždňami") || text.includes("týždne") || text.includes("týždňom")) {
        days = -(num * 7);
    } else if (text.includes("month") || text.includes("mesiac") || text.includes("mesiacmi") || text.includes("mesiace")) {
        days = -(num * 30);
    } else if (text.includes("year") || text.includes("rok") || text.includes("rokmi") || text.includes("roky")) {
        days = -(num * 365);
    } else if (text.includes("hour") || text.includes("hodin") || text.includes("minute") || text.includes("minút") || text.includes("second") || text.includes("sekund")) {
        isToday = true;
    } else if (text.includes("live") || text.includes("naživo") || text.includes("premiéra") || text.includes("premiere") || text.includes("streamed") || text.includes("streamované")) {
        isToday = true;
    } else {
        return "1970-01-01";
    }
    
    if (isToday) return "TODAY";
    return `TODAY ${days} days`;
}

function resolveRelativeTime(relativeTime, videoId) {
    if (relativeTime) return relativeTime;
    let anchor = html.indexOf(`"videoId":"${videoId}"`);
    if (anchor !== -1) {
        let chunk = html.substring(anchor, anchor + 4000);
        let m = chunk.match(/"text"\s*:\s*"([^"]*(ago|pred|hodin|minút|sekúnd|dň|týžd|mesiac|rok|Stream|Premi)[^"]*)"/i);
        if (m) return m[1];
        m = chunk.match(/"publishedTimeText"\s*:\s*\{\s*"simpleText"\s*:\s*"([^"]+)"/);
        if (m) return m[1];
    }
    return "";
}

function traverse(node) {
    if (node && typeof node === 'object') {
        if (node.lockupViewModel) {
            try {
                let parts = node.lockupViewModel.metadata.lockupMetadataViewModel.metadata.contentMetadataViewModel.metadataRows[0].metadataParts;
                let title = node.lockupViewModel.metadata.lockupMetadataViewModel.title.content;
                let url = node.lockupViewModel.rendererContext.commandContext.onTap.innertubeCommand.commandMetadata.webCommandMetadata.url;
                let relativeTime = "";
                if (parts.length > 0) {
                    relativeTime = parts[parts.length - 1].text.content;
                }
                let videoId = url.match(/\/watch\?v=([^&]+)/)[1];
                let resolved = resolveRelativeTime(relativeTime, videoId);
                videos.push({ title, resolved, date: parseRelativeDate(resolved) });
            } catch(e) {}
        } else if (node.videoWithContextRenderer) {
            try {
                let renderer = node.videoWithContextRenderer;
                let videoId = renderer.videoId;
                let title = renderer.headline.runs[0].text;
                let relativeTime = "";
                try {
                    relativeTime = renderer.publishedTimeText.simpleText;
                } catch(e) {
                    try { relativeTime = renderer.publishedTimeText.runs[0].text; } catch(e) {}
                }
                let resolved = resolveRelativeTime(relativeTime, videoId);
                videos.push({ title, resolved, date: parseRelativeDate(resolved) });
            } catch(e) {}
        } else if (node.videoRenderer) {
            try {
                let renderer = node.videoRenderer;
                let videoId = renderer.videoId;
                let title = renderer.title.runs[0].text;
                let relativeTime = "";
                try {
                    relativeTime = renderer.publishedTimeText.simpleText;
                } catch(e) {
                    try { relativeTime = renderer.publishedTimeText.runs[0].text; } catch(e) {}
                }
                let resolved = resolveRelativeTime(relativeTime, videoId);
                videos.push({ title, resolved, date: parseRelativeDate(resolved) });
            } catch(e) {}
        }
        Object.values(node).forEach(traverse);
    }
}
traverse(obj);
console.log(videos.slice(0, 15).map(v => `${v.date} | ${v.resolved} | ${v.title}`).join("\n"));
