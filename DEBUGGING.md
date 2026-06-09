# Debugging Approach

Lessons captured from the June 2026 download speed / progress display investigation.

---

## Core Rules

### 1. Change one thing at a time
Make exactly one change, verify it works, then move to the next.
Never stack multiple changes in one step — when something breaks you won't know which change caused it.

### 2. Always verify from the user's perspective
Run the code exactly as the user runs it. If the user runs `just ta3`, test with `just ta3`.
Do not test with wrapper scripts, pipes, or redirects that change the environment —
they produce different behaviour (e.g. `cmd | tee file.log` breaks ANSI cursor rewrites;
`cmd 2>&1` merges streams in ways that don't reflect what the user sees).

### 3. No guessing — prove every assumption
Before changing anything, write a small throwaway script that **proves** the
specific assumption you're acting on. Examples from this session:

- "Is yt-dlp output actually reaching Node.js?" → write a 30-line script that
  spawns it and prints every data event. Run it, read the output, *then* decide.
- "Does the master m3u8 actually have multiple variants?" → fetch it and print
  them before writing any parsing code.

If you can't prove it, don't build on it.

### 4. Establish a working baseline first
Before optimising or changing behaviour, confirm the *current* code still works.
If it already works (even if slowly), that is your safety net.
Touch nothing until you have a green baseline you can return to.

### 5. Revert fast when stuck
If two consecutive changes both fail to fix the issue, stop and revert to the
last known-good state. Continuing to layer changes on a broken foundation just
creates more noise.

---

## Debugging Workflow

```
1. Reproduce the problem as the user would see it
       ↓
2. Form one specific hypothesis ("X is causing Y because Z")
       ↓
3. Write a minimal script that proves/disproves the hypothesis
       ↓
4. If disproved → go back to step 2 with a new hypothesis
   If proved    → make exactly one targeted change
       ↓
5. Verify the change fixed the problem (run as user would)
       ↓
6. Only then make the next change
```

---

## What Went Wrong This Session

| Step | Mistake | Consequence |
|------|---------|-------------|
| Switched ffmpeg → yt-dlp | Did not first verify yt-dlp output reached Node when spawned from it | Chased a buffering problem for hours |
| Changed display (cursor-up → save/restore) | Did not test in a real TTY | Broke the display completely (repeated lines) |
| Tested with `cmd \| head` pipe | Pipe caused Broken Pipe in yt-dlp, masking real behaviour | False positive on first test |
| Tested with `node script.js 2>&1` piped to file | ANSI codes written literally, cursor-up logic invisible | Could not see whether display actually worked |
| Changed multiple things at once (stdio, env, display, command) | Too many variables; impossible to isolate root cause | Hours lost |

**Root cause of the whole problem was simple:** ffmpeg was fetching the 720p stream
(2.7 Mbps) when we only needed audio. Selecting the 360p variant (950 kbps) from the
master playlist gave a 2.8x speedup with zero display or architecture changes required.

---

## Quick Reference: How to Test This Project

```bash
# Run exactly as user does
just ta3

# Check what's downloading / running
ps aux | grep -E "ffmpeg|yt-dlp|node"

# Check files actually appear
ls -lh downloads/ta3/$(date +%Y/%m/%d)/

# Clear today and rerun cleanly
rm -rf downloads/ta3/$(date +%Y/%m/%d)
# remove today's URLs from download_history.json first
just ta3
```

---

## Spawn Gotchas (Node.js → child process)

| Issue | Symptom | Fix |
|-------|---------|-----|
| Python output buffering | `data` events never fire on pipe despite process running | Set `PYTHONUNBUFFERED=1` in env, or use `--no-colors --progress --newline` |
| stdin inherited from TTY | Child blocks waiting for terminal input | Use `stdio: ['ignore', 'pipe', 'pipe']` |
| `just` wrapping a child | `just` may buffer stdout/stderr of its child | Spawn the tool directly instead of via `just` |
| Testing with `\| tee` or `\| head` | Kills child via SIGPIPE; ANSI cursor codes written literally | Test in a real TTY for display, use log files only for exit codes / file creation |
