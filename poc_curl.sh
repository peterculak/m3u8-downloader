#!/bin/bash
URL=$(yt-dlp -g -f "bestaudio[ext=m4a]" "https://youtu.be/N4oRw8HmqGw")
curl -s -D - -o /dev/null -H "Range: bytes=0-" "$URL" | grep -i Content-Length
