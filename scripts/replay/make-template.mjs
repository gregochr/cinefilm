// Makes the stand-in film's picture and sound: four seconds of a drawn canvas (96 frames at 24 a second) encoded
// to H.264, and one frame of silence encoded to AAC, both by Chrome itself (WebCodecs), written to
// scripts/replay/out/template.json. No film, no download. Every segment of the stand-in film is this one group of
// pictures and that silence with their own time stamps (see ts.mjs).
//
// Run: node scripts/replay/make-template.mjs <a directory for the throwaway browser profile>
// It needs the installed Chrome and cannot run in CI; `silentFrame` and `nalTypes` are tested.
import http from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchChrome } from './cdp.mjs';
import { printable } from './text.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
export const TEMPLATE_PATH = join(HERE, 'out', 'template.json');

// Runs in the page. Returns { codec, width, height, fps, frames: [{ key, ts, b64 }] } or { error }.
const ENCODE = `(async () => {
  const W = 640, H = 360, FPS = 24, N = 96;
  if (typeof VideoEncoder === 'undefined') return { error: 'no VideoEncoder in this page (secure context: ' + isSecureContext + ')' };
  const canvas = new OffscreenCanvas(W, H);
  const ctx = canvas.getContext('2d');
  // One attempt with one configuration: every frame must come out, in time order (no dropped or reordered frames).
  const attempt = async (config) => {
    const chunks = [];
    let failure = null;
    const encoder = new VideoEncoder({
      output: (chunk) => {
        const data = new Uint8Array(chunk.byteLength);
        chunk.copyTo(data);
        chunks.push({ key: chunk.type === 'key', ts: chunk.timestamp, data });
      },
      error: (e) => { failure = String(e); },
    });
    encoder.configure(config);
    for (let i = 0; i < N; i += 1) {
      ctx.fillStyle = '#101418';
      ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = '#d98a2b';
      ctx.fillRect(Math.round((i / N) * (W - 48)), H / 2 - 24, 48, 48);
      ctx.fillStyle = '#e8e8e8';
      ctx.font = '26px sans-serif';
      ctx.fillText('stand-in film', 24, 44);
      const frame = new VideoFrame(canvas, { timestamp: Math.round((i * 1e6) / FPS), duration: Math.round(1e6 / FPS) });
      encoder.encode(frame, { keyFrame: i === 0 });
      frame.close();
      // Never let the encoder's queue build up: a busy encoder may drop frames.
      while (encoder.encodeQueueSize > 1 && !failure) await new Promise((r) => setTimeout(r, 5));
    }
    await encoder.flush();
    encoder.close();
    if (failure) return { why: failure };
    if (chunks.length !== N) return { why: chunks.length + ' frames, not ' + N };
    if (!chunks.every((c, i) => i === 0 || c.ts > chunks[i - 1].ts)) return { why: 'frames out of time order' };
    return { chunks };
  };
  const tried = [];
  let config = null;
  let chunks = null;
  for (const codec of ['avc1.64001e', 'avc1.4d001e', 'avc1.42001e']) {
    for (const latencyMode of ['quality', 'realtime']) {
      const c = { codec, width: W, height: H, bitrate: 150000, framerate: FPS, latencyMode, avc: { format: 'annexb' } };
      let supported = false;
      try {
        supported = (await VideoEncoder.isConfigSupported(c)).supported;
      } catch (e) {
        tried.push(codec + '/' + latencyMode + ': ' + e);
        continue;
      }
      if (!supported) { tried.push(codec + '/' + latencyMode + ': not supported'); continue; }
      const result = await attempt(c);
      if (result.chunks) { config = c; chunks = result.chunks; tried.push(codec + '/' + latencyMode + ': ok'); break; }
      tried.push(codec + '/' + latencyMode + ': ' + result.why);
    }
    if (chunks) break;
  }
  if (!chunks) return { error: 'no usable H.264 encoding: ' + tried.join('; ') };
  const b64 = (bytes) => {
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s);
  };
  // The sound track: silence, as AAC-LC frames with their ADTS headers (1024 samples each at 48 kHz, stereo).
  const audio = await (async () => {
    if (typeof AudioEncoder === 'undefined') return { error: 'no AudioEncoder' };
    const cfg = { codec: 'mp4a.40.2', sampleRate: 48000, numberOfChannels: 2, bitrate: 96000, aac: { format: 'adts' } };
    try {
      if (!(await AudioEncoder.isConfigSupported(cfg)).supported) return { error: 'AAC with ADTS is not supported' };
    } catch (e) {
      return { error: String(e) };
    }
    const out = [];
    let failure = null;
    const encoder = new AudioEncoder({
      output: (chunk) => { const d = new Uint8Array(chunk.byteLength); chunk.copyTo(d); out.push(d); },
      error: (e) => { failure = String(e); },
    });
    encoder.configure(cfg);
    for (let i = 0; i < 40; i += 1) {
      const data = new AudioData({
        format: 'f32-planar', sampleRate: 48000, numberOfFrames: 1024, numberOfChannels: 2,
        timestamp: Math.round((i * 1024 * 1e6) / 48000), data: new Float32Array(2048),
      });
      encoder.encode(data);
      data.close();
    }
    await encoder.flush();
    encoder.close();
    if (failure) return { error: failure };
    return { frames: out.map(b64) };
  })();
  return {
    codec: config.codec, tried, width: W, height: H, fps: FPS,
    frames: chunks.map((c) => ({ key: c.key, ts: c.ts, b64: b64(c.data) })),
    audio,
  };
})()`;

/**
 * One frame of silence from what the encoder made: a frame from the middle (past the encoder's start-up), checked
 * to be exactly one ADTS frame of AAC-LC at 48 kHz stereo. Null when there is none to trust.
 */
export function silentFrame(audio) {
  if (!audio || !Array.isArray(audio.frames) || audio.frames.length < 20) return null;
  const frame = Buffer.from(audio.frames[Math.floor(audio.frames.length / 2)], 'base64');
  if (frame.length < 8 || frame[0] !== 0xff || (frame[1] & 0xf6) !== 0xf0) return null; // the ADTS sync word, layer 0
  const profile = (frame[2] >> 6) + 1; // 2 is AAC-LC
  const rateIndex = (frame[2] >> 2) & 0x0f; // 3 is 48 kHz
  const channels = ((frame[2] & 0x01) << 2) | (frame[3] >> 6);
  const length = ((frame[3] & 0x03) << 11) | (frame[4] << 3) | (frame[5] >> 5);
  const blocks = (frame[6] & 0x03) + 1;
  if (profile !== 2 || rateIndex !== 3 || channels !== 2 || length !== frame.length || blocks !== 1) return null;
  return frame;
}

/** The NAL unit types in an Annex B buffer. */
export function nalTypes(buffer) {
  const types = [];
  for (let i = 0; i + 3 < buffer.length; i += 1) {
    if (buffer[i] === 0 && buffer[i + 1] === 0 && buffer[i + 2] === 1) {
      types.push(buffer[i + 3] & 0x1f);
      i += 3;
    }
  }
  return types;
}

export async function makeTemplate({ profileRoot, onSpawn }) {
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end('<!doctype html><title>template</title>');
  });
  let chrome = null;
  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', resolve);
    });
    chrome = await launchChrome({ profileRoot, onSpawn });
    await chrome.page.send('Page.enable');
    await chrome.page.send('Page.navigate', { url: `http://127.0.0.1:${server.address().port}/` });
    await new Promise((resolve) => setTimeout(resolve, 800));
    const result = await chrome.page.evaluate(ENCODE);
    if (result.error) throw new Error(`template: ${printable(result.error, 300)}`);
    return writeTemplate(result);
  } finally {
    if (chrome) await chrome.close();
    server.close();
  }
}

// Checks what Chrome made and writes it: 96 frames in time order, the first a key frame carrying SPS and PPS.
function writeTemplate(result) {
  if (!Array.isArray(result.frames) || result.frames.length === 0) throw new Error('template: Chrome made no frames');
  const frames = result.frames.map((f) => ({ key: f.key, ts: f.ts, data: Buffer.from(f.b64, 'base64') }));
  const ordered = frames.every((f, i) => i === 0 || f.ts > frames[i - 1].ts);
  const first = nalTypes(frames[0].data);
  const problems = [];
  if (frames.length !== 96) problems.push(`${frames.length} frames, not 96`);
  if (!ordered) problems.push('frames are not in time order (the encoder reordered them)');
  if (!frames[0].key || !first.includes(7) || !first.includes(8) || !first.includes(5)) {
    problems.push(`the first frame is not a key frame with SPS and PPS (NAL types ${first.join(',')})`);
  }
  if (problems.length) throw new Error(`template: ${problems.join('; ')}`);
  const silence = silentFrame(result.audio);
  mkdirSync(dirname(TEMPLATE_PATH), { recursive: true });
  writeFileSync(TEMPLATE_PATH, JSON.stringify({
    codec: result.codec, width: result.width, height: result.height, fps: result.fps,
    frames: result.frames.map((f) => ({ key: f.key, b64: f.b64 })),
    // One ADTS frame of silence, or null when Chrome could not make one (the film then has no sound track).
    silence: silence ? silence.toString('base64') : null,
  }));
  const bytes = frames.reduce((sum, f) => sum + f.data.length, 0);
  return {
    codec: result.codec, tried: result.tried, frames: frames.length, bytes,
    keyFrames: frames.filter((f) => f.key).length, firstNals: first,
    sound: silence ? `a ${silence.length}-byte frame of silence` : `none (${result.audio?.error ?? 'frames not usable'})`,
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const profileRoot = process.argv[2];
  if (!profileRoot) {
    console.error(printable('usage: node scripts/replay/make-template.mjs <a directory for the throwaway browser profile>', 200));
    process.exit(2);
  }
  const made = await makeTemplate({ profileRoot });
  console.log(printable(JSON.stringify(made), 2000));
  console.log(printable(`written: ${TEMPLATE_PATH}`, 500));
}
