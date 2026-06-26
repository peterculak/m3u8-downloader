fetch('https://www.youtube.com/@Bra%C5%88oZ%C3%A1vodsk%C3%BDNa%C5%BEivo/streams', {
    headers: { 'Accept-Language': 'en-US,en;q=0.9', 'Cookie': 'CONSENT=YES+cb.20210328-17-p0.en+FX+478' }
})
.then(res => res.text())
.then(html => {
    const rawMatches = [...html.matchAll(/"publishedTimeText"\s*:\s*\{[^}]+\}/g)];
    console.log('publishedTimeText matches:', rawMatches.slice(0, 10).map(m => m[0]));
    
    // Also look for simpleText or runs for time
    const runsMatches = [...html.matchAll(/"text"\s*:\s*"([^"]*(?:ago|pred|hodin|minút|sekúnd|dň|týžd|mesiac|rok|Stream|Premi)[^"]*)"/ig)];
    console.log('Fallback matches:', runsMatches.slice(0, 10).map(m => m[1]));
});
