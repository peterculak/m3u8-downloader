/**
 * share.js - WhatsApp file sharing utility
 * Usage: node share.js /path/to/file.m4a
 * Reads whatsAppPhone from config.json and sends the file via WhatsApp Desktop.
 */
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const filePath = process.argv[2];
if (!filePath) {
  console.error('Usage: node share.js <filepath>');
  process.exit(1);
}

const configPath = path.join(__dirname, 'config.json');
if (!fs.existsSync(configPath)) {
  console.error('config.json not found');
  process.exit(1);
}

const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const phoneNumber = config.whatsAppPhone;
if (!phoneNumber) {
  console.error('whatsAppPhone not set in config.json');
  process.exit(1);
}

console.log(`Sharing "${path.basename(filePath)}" via WhatsApp to ${config.whatsAppContact || phoneNumber}...`);

const escapedPath = filePath.replace(/"/g, '\\"');

const appleScript = `
-- Copy file to clipboard as a file reference
set thePath to POSIX file "${escapedPath}"
set the clipboard to thePath as «class furl»

-- Open chat directly via URL scheme (bypasses search bar entirely)
do shell script "open 'whatsapp://send?phone=${phoneNumber}'"
delay 3.0 -- Wait for WhatsApp to open the chat and focus the message input

tell application "System Events"
  tell process "WhatsApp"
    -- Paste the file
    keystroke "v" using {command down}
    delay 3.0

    -- Press Enter to send from the media preview screen
    key code 36
    delay 1.0
  end tell
end tell
`;

const proc = spawn('osascript', []);
let stderr = '';
proc.stderr.on('data', (data) => { stderr += data.toString(); });
proc.stdin.write(appleScript);
proc.stdin.end();

proc.on('close', (code) => {
  if (code === 0) {
    console.log('Shared successfully.');
  } else {
    console.error(`Failed to share. Error: ${stderr.trim()}`);
    process.exit(1);
  }
});

proc.on('error', (err) => {
  console.error(`Process error: ${err.message}`);
  process.exit(1);
});
