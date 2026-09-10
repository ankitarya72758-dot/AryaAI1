import express from "express";
import multer from "multer";
import fs from "fs";
import path from "path";
import os from "os";
import crypto from "crypto";
import { spawn } from "child_process";

const app = express();
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 15 * 1024 * 1024 } });
const PORT = process.env.PORT || 3000;
const RUNWAY_KEY = process.env.RUNWAYML_API_SECRET;
const MODEL = process.env.RUNWAY_MODEL || "gen4.5";
const jobs = new Map();

if (!RUNWAY_KEY) {
  console.warn("RUNWAYML_API_SECRET is not set. Create it before generating.");
}

app.use(express.json());
app.use("/outputs", express.static(path.resolve("outputs")));

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function runwayCreate(promptImageDataUri, promptText) {
  const r = await fetch("https://api.dev.runwayml.com/v1/image_to_video", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${RUNWAY_KEY}`,
      "X-Runway-Version": "2024-11-06"
    },
    body: JSON.stringify({
      model: MODEL,
      promptImage: promptImageDataUri,
      promptText: promptText || "Subtle natural cinematic motion, realistic camera movement.",
      ratio: "1280:720",
      duration: 10
    })
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`Runway create ${r.status}: ${text}`);
  return JSON.parse(text);
}

async function runwayWait(taskId) {
  for (let i = 0; i < 180; i++) {
    const r = await fetch(`https://api.dev.runwayml.com/v1/tasks/${taskId}`, {
      headers: {
        "Authorization": `Bearer ${RUNWAY_KEY}`,
        "X-Runway-Version": "2024-11-06"
      }
    });
    const data = await r.json();
    if (data.status === "SUCCEEDED") return data.output?.[0];
    if (data.status === "FAILED" || data.status === "CANCELED") {
      throw new Error(`Runway task ${data.status}: ${data.failureCode || "unknown"}`);
    }
    await sleep(6000);
  }
  throw new Error("Runway task timed out.");
}

function runFfmpeg(args) {
  return new Promise((resolve, reject) => {
    const p = spawn("ffmpeg", args);
    let err = "";
    p.stderr.on("data", d => err += d.toString());
    p.on("close", code => code === 0 ? resolve() : reject(new Error(err.slice(-4000))));
  });
}

async function download(url, file) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`Download failed: ${r.status}`);
  const buf = Buffer.from(await r.arrayBuffer());
  fs.writeFileSync(file, buf);
}

async function generateJob(jobId, imageBuffer, prompt) {
  const dir = path.resolve("work", jobId);
  fs.mkdirSync(dir, { recursive: true });
  fs.mkdirSync(path.resolve("outputs"), { recursive: true });

  try {
    let frameBuffer = imageBuffer;
    const clips = [];

    for (let i = 0; i < 30; i++) {
      jobs.get(jobId).progress = Math.round((i / 30) * 100);

      const mime = "image/jpeg";
      const dataUri = `data:${mime};base64,${frameBuffer.toString("base64")}`;
      const task = await runwayCreate(dataUri, prompt);
      const videoUrl = await runwayWait(task.id);

      const clip = path.join(dir, `clip_${String(i).padStart(2, "0")}.mp4`);
      await download(videoUrl, clip);
      clips.push(clip);

      // Extract the final frame so the next generation starts from the previous shot.
      const nextFrame = path.join(dir, `frame_${String(i).padStart(2, "0")}.jpg`);
      await runFfmpeg(["-y", "-sseof", "-0.05", "-i", clip, "-frames:v", "1", "-q:v", "2", nextFrame]);
      frameBuffer = fs.readFileSync(nextFrame);
    }

    const listFile = path.join(dir, "concat.txt");
    fs.writeFileSync(listFile, clips.map(c => `file '${c.replaceAll("'", "'\\''")}'`).join("\n"));

    const outputName = `${jobId}.mp4`;
    const output = path.resolve("outputs", outputName);

    try {
      await runFfmpeg(["-y", "-f", "concat", "-safe", "0", "-i", listFile, "-c", "copy", output]);
    } catch {
      await runFfmpeg(["-y", "-f", "concat", "-safe", "0", "-i", listFile, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", output]);
    }

    jobs.get(jobId).status = "SUCCEEDED";
    jobs.get(jobId).progress = 100;
    jobs.get(jobId).videoUrl = `/outputs/${outputName}`;
  } catch (e) {
    jobs.get(jobId).status = "FAILED";
    jobs.get(jobId).error = e.message;
  }
}

app.post("/generate", upload.single("image"), async (req, res) => {
  if (!RUNWAY_KEY) return res.status(500).json({ error: "RUNWAYML_API_SECRET missing on server." });
  if (!req.file) return res.status(400).json({ error: "image is required" });

  const jobId = crypto.randomUUID();
  jobs.set(jobId, { status: "PENDING", progress: 0 });
  generateJob(jobId, req.file.buffer, req.body.prompt || "");
  res.json({ jobId });
});

app.get("/status/:jobId", (req, res) => {
  const job = jobs.get(req.params.jobId);
  if (!job) return res.status(404).json({ error: "job not found" });
  res.json(job);
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Arya AI server running on http://0.0.0.0:${PORT}`);
  console.log(`Model: ${MODEL}`);
});
