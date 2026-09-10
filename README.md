# Arya AI Runway backend

This server keeps the Runway API key off the Android APK and generates a 5-minute video as 30 sequential 10-second Gen-4.5 image-to-video clips. The last frame of each clip becomes the input image for the next clip, then FFmpeg concatenates the clips.

## Requirements

- Node.js 20+
- FFmpeg installed and available as `ffmpeg` in PATH
- A Runway Dev API key

## Start

```bash
cd server
npm install
```

Set the environment variable:

### Windows PowerShell
```powershell
$env:RUNWAYML_API_SECRET="YOUR_KEY"
$env:RUNWAY_MODEL="gen4.5"
npm start
```

### macOS/Linux
```bash
export RUNWAYML_API_SECRET="YOUR_KEY"
export RUNWAY_MODEL="gen4.5"
npm start
```

The server listens on port 3000.

## Android connection

- Android emulator: use `http://10.0.2.2:3000`
- Physical phone on the same Wi-Fi as the computer: use `http://YOUR_PC_LAN_IP:3000`
- Production: put this backend behind HTTPS and use the HTTPS URL.

## Important

Gen-4.5 currently supports image-to-video durations up to 10 seconds. This project therefore uses 30 x 10-second generations for a 5-minute result. Runway's published Gen-4.5 rate is 12 credits/second, so a full 300-second generation is roughly 3,600 credits before retries/other costs.

Do not expose the Runway key in Android code. The Android app talks only to this backend.
