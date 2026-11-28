// Builds the stand-in film's segments: MPEG-TS, as CineFilm serves (H.264 and AAC in TS under plain variants),
// each one the same four-second group of pictures and the same silence (the template: a helper that arrives with
// the driver makes it from Chrome's encoder; the tests build their own) with their own time stamps, padded to the
// size its rung's average bitrate implies. Two ways to pad:
//   'media' (the default): filler inside the picture's own stream (an H.264 SEI filler payload in every frame),
//           which the player's demuxer keeps, so the browser's media buffer weighs what a real rung would;
//   'null':  null packets after the frames, which the demuxer drops: the browser's buffer then holds only the
//           small picture and the silence, however heavy the rung.
import { readFileSync } from 'node:fs';

const PACKET = 188;
const PAT_PID = 0;
const PMT_PID = 0x1000;
const VIDEO_PID = 0x100;
const AUDIO_PID = 0x101;
const NULL_PID = 0x1fff;
const CLOCK = 90000;
/** One AAC frame is 1024 samples at 48 kHz. */
const AUDIO_FRAME_TICKS = (1024 * CLOCK) / 48000;
const AUDIO_FRAMES_PER_PES = 8;
/** Where the film's time stamps start, as encoders usually leave a little room before zero. */
const BASE_PTS = 10 * CLOCK;
const AUD = Buffer.from([0, 0, 0, 1, 0x09, 0xf0]);

/**
 * The audio rates, in kbit/s, of the rungs CineFilm encodes (Rung.java: FLOOR_AUDIO_BITRATE, LOW_AUDIO_BITRATE,
 * AUDIO_BITRATE; a test reads them from the source). The template holds a stretch of AAC at each, so that a
 * segment's sound weighs what the real rung's does: Chrome's audio buffer counts about 93 bytes a frame on top of the
 * frame's own bytes (train-lookahead-plan.md, step 0.2), and with silence it never fills as it really does.
 */
export const AUDIO_KBPS = [64, 96, 128];

export function loadTemplate(path) {
  const raw = JSON.parse(readFileSync(path, 'utf8'));
  return {
    codec: raw.codec, fps: raw.fps, width: raw.width, height: raw.height,
    frames: raw.frames.map((f) => ({ key: f.key, data: Buffer.from(f.b64, 'base64') })),
    /** One ADTS frame of silence, or null: the film then has no sound track. */
    silence: raw.silence ? Buffer.from(raw.silence, 'base64') : null,
    /** Seconds of film in one segment: the template's frames at its frame rate. */
    seconds: raw.frames.length / raw.fps,
    /** ADTS frames of noise at each audio rate (kbit/s), or null for a template made before they were kept. */
    noise: raw.noise ? Object.fromEntries(Object.entries(raw.noise).map(([kbps, frames]) => [kbps, frames.map((b) => Buffer.from(b, 'base64'))])) : null,
  };
}

// MPEG-2 CRC32 for the PAT and PMT sections.
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i += 1) {
    let c = i << 24;
    for (let k = 0; k < 8; k += 1) c = c & 0x80000000 ? (c << 1) ^ 0x04c11db7 : c << 1;
    table[i] = c >>> 0;
  }
  return table;
})();
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const b of bytes) crc = ((crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xff]) >>> 0;
  return crc >>> 0;
}

function header(pid, start, counter, adaptation, payload) {
  const control = (adaptation ? 0x20 : 0) | (payload ? 0x10 : 0);
  return Buffer.from([0x47, (start ? 0x40 : 0) | ((pid >> 8) & 0x1f), pid & 0xff, control | (counter & 0x0f)]);
}

// A table section in one packet: pointer field, the section, then 0xff to the end.
function sectionPacket(pid, section) {
  const withCrc = Buffer.concat([section, Buffer.alloc(4)]);
  withCrc.writeUInt32BE(crc32(section), section.length);
  const packet = Buffer.alloc(PACKET, 0xff);
  header(pid, true, 0, false, true).copy(packet, 0);
  packet[4] = 0; // pointer field
  withCrc.copy(packet, 5);
  return packet;
}

const PAT = sectionPacket(PAT_PID, Buffer.from([
  0x00, 0xb0, 13, // table id, section length (5 + 4 + 4)
  0x00, 0x01, 0xc1, 0x00, 0x00, // stream id 1, version 0 current, section 0 of 0
  0x00, 0x01, 0xe0 | (PMT_PID >> 8), PMT_PID & 0xff, // programme 1 -> its map
]));

const PMT_HEAD = [
  0x00, 0x01, 0xc1, 0x00, 0x00, // programme 1, version 0 current, section 0 of 0
  0xe0 | (VIDEO_PID >> 8), VIDEO_PID & 0xff, // clock reference on the video stream
  0xf0, 0x00, // no programme descriptors
  0x1b, 0xe0 | (VIDEO_PID >> 8), VIDEO_PID & 0xff, 0xf0, 0x00, // H.264 video
];
const PMT_VIDEO_ONLY = sectionPacket(PMT_PID, Buffer.from([0x02, 0xb0, 18, ...PMT_HEAD])); // 9 + 5 + 4
const PMT_WITH_SOUND = sectionPacket(PMT_PID, Buffer.from([
  0x02, 0xb0, 23, ...PMT_HEAD, // 9 + 5 + 5 + 4
  0x0f, 0xe0 | (AUDIO_PID >> 8), AUDIO_PID & 0xff, 0xf0, 0x00, // AAC with ADTS headers
]));

const NULL_PACKET = (() => {
  const packet = Buffer.alloc(PACKET, 0xff);
  header(NULL_PID, false, 0, false, true).copy(packet, 0);
  return packet;
})();

// Five bytes of a 33-bit time stamp; `prefix` is 2 (a PTS alone), 3 (a PTS before a DTS) or 1 (the DTS).
function stamp(prefix, value) {
  return Buffer.from([
    (prefix << 4) | ((Math.floor(value / 2 ** 30) & 0x07) << 1) | 1,
    Math.floor(value / 2 ** 22) & 0xff,
    ((Math.floor(value / 2 ** 15) & 0x7f) << 1) | 1,
    Math.floor(value / 2 ** 7) & 0xff,
    ((value % 128) << 1) | 1,
  ]);
}

// An SEI NAL unit holding one filler payload of `bytes` 0xff bytes: decoders skip it, demuxers keep it.
function fillerNal(bytes) {
  const size = [];
  let left = bytes;
  while (left >= 255) {
    size.push(0xff);
    left -= 255;
  }
  size.push(left);
  return Buffer.concat([Buffer.from([0, 0, 0, 1, 0x06, 0x03, ...size]), Buffer.alloc(bytes, 0xff), Buffer.from([0x80])]);
}

// One frame of picture as a PES packet (PTS only: the template has no reordered frames), with `filler` bytes of
// SEI filler between the access unit delimiter and the frame.
function framePackets(frame, pts, counterFrom, filler) {
  const pes = Buffer.concat([
    Buffer.from([0x00, 0x00, 0x01, 0xe0, 0x00, 0x00, 0x80, 0x80, 0x05]),
    stamp(2, pts),
    AUD,
    ...(filler > 0 ? [fillerNal(filler)] : []),
    frame.data,
  ]);
  return pesPackets(VIDEO_PID, pes, counterFrom);
}

// `count` frames of sound as one PES packet, stamped with the time of the first. `sound` is one frame (repeated) or a
// list of frames, of which frame k of the film takes number k modulo the list's length.
function silencePackets(sound, count, pts, counterFrom, k) {
  const frames = Array.isArray(sound)
    ? Array.from({ length: count }, (_, i) => sound[(k + i) % sound.length])
    : new Array(count).fill(sound);
  const length = 3 + 5 + frames.reduce((sum, frame) => sum + frame.length, 0); // what follows the length field
  const pes = Buffer.concat([
    Buffer.from([0x00, 0x00, 0x01, 0xc0, length >> 8, length & 0xff, 0x80, 0x80, 0x05]),
    stamp(2, pts),
    ...frames,
  ]);
  return pesPackets(AUDIO_PID, pes, counterFrom);
}

// A PES packet cut into TS packets.
function pesPackets(pid, pes, counterFrom) {
  const packets = [];
  let counter = counterFrom;
  for (let offset = 0; offset < pes.length;) {
    const left = pes.length - offset;
    const packet = Buffer.alloc(PACKET, 0xff);
    const first = offset === 0;
    if (left >= PACKET - 4) {
      header(pid, first, counter, false, true).copy(packet, 0);
      pes.copy(packet, 4, offset, offset + PACKET - 4);
      offset += PACKET - 4;
    } else {
      // The last packet: an adaptation field of stuffing fills the space before the payload.
      const stuffing = PACKET - 4 - left; // bytes of adaptation field, its length byte included
      header(pid, first, counter, true, true).copy(packet, 0);
      packet[4] = stuffing - 1;
      if (stuffing > 1) packet[5] = 0x00; // no flags; the rest is already 0xff
      pes.copy(packet, 4 + stuffing, offset);
      offset = pes.length;
    }
    packets.push(packet);
    counter = (counter + 1) & 0x0f;
  }
  return { packets, counter };
}

// What each frame's SEI filler holds, so that the segment comes close to `targetBytes`: what the padding has to add,
// shared among the frames, less the TS packet headers it will be carried in, the half packet of stuffing each
// frame ends with on average, and the filler's own size bytes (one in 255). It falls a little short on purpose;
// null packets make up the difference.
function fillerSize(template, targetBytes, audioKbps) {
  const spare = (targetBytes - bareSegmentBytes(template, audioKbps)) * ((PACKET - 4) / PACKET);
  const each = (spare / template.frames.length - PACKET / 2) / (1 + 1 / 255);
  return Math.max(0, Math.floor(each) - 64);
}

// The picture of segment `index`: every frame of the template, stamped from the segment's own start.
function videoPackets(template, index, segmentTicks, filler) {
  const ticksPerFrame = CLOCK / template.fps;
  const base = BASE_PTS + Math.round(index * segmentTicks);
  const packets = [];
  let counter = 0;
  template.frames.forEach((frame, i) => {
    const made = framePackets(frame, base + Math.round(i * ticksPerFrame), counter, filler);
    packets.push(...made.packets);
    counter = made.counter;
  });
  return packets;
}

// The sound runs on its own unbroken clock across segments: frame k starts at k x 1920 ticks, and a segment
// carries the frames that start inside it (187 or 188 of them).
function audioPackets(sound, index, segmentTicks) {
  const firstFrame = Math.ceil((index * segmentTicks) / AUDIO_FRAME_TICKS);
  const endFrame = Math.ceil(((index + 1) * segmentTicks) / AUDIO_FRAME_TICKS);
  const packets = [];
  let counter = 0;
  for (let k = firstFrame; k < endFrame; k += AUDIO_FRAMES_PER_PES) {
    const count = Math.min(AUDIO_FRAMES_PER_PES, endFrame - k);
    const made = silencePackets(sound, count, BASE_PTS + Math.round(k * AUDIO_FRAME_TICKS), counter, k);
    packets.push(...made.packets);
    counter = made.counter;
  }
  return packets;
}

// The sound of a segment at an audio rate: the template's noise at that rate, else (no rate given, or a template with
// none at it) its one frame of silence, or null when the film has no sound at all.
function soundFor(template, audioKbps) {
  if (!template.silence) return null;
  const noise = audioKbps === undefined ? null : template.noise?.[audioKbps];
  return noise?.length ? noise : template.silence;
}

/**
 * Segment `index` of the film as bytes: the tables, the template's frames and sound stamped for that segment,
 * padded to `targetBytes` (rounded down to whole packets; never fewer bytes than the frames need). `fill` is
 * 'media' (filler inside every frame, topped up with a few null packets) or 'null' (null packets only).
 * `audioKbps` is the rung's audio rate: its sound is the template's noise at that rate, which weighs what that
 * rung's audio does; left out, the sound is silence.
 */
export function buildSegment(template, index, targetBytes, fill = 'media', audioKbps = undefined) {
  const filler = fill === 'media' ? fillerSize(template, targetBytes, audioKbps) : 0;
  const sound = soundFor(template, audioKbps);
  const segmentTicks = template.frames.length * (CLOCK / template.fps);
  const packets = [
    PAT,
    sound ? PMT_WITH_SOUND : PMT_VIDEO_ONLY,
    ...videoPackets(template, index, segmentTicks, filler),
    ...(sound ? audioPackets(sound, index, segmentTicks) : []),
  ];
  const wanted = Math.floor(targetBytes / PACKET);
  const nulls = new Array(Math.max(0, wanted - packets.length)).fill(NULL_PACKET);
  return Buffer.concat([...packets, ...nulls]);
}

/** The bytes the frames, the sound at `audioKbps` and the tables need, with no padding; kept per audio rate. */
export function bareSegmentBytes(template, audioKbps = undefined) {
  template.bareBytes ??= new Map();
  if (!template.bareBytes.has(audioKbps)) template.bareBytes.set(audioKbps, buildSegment(template, 0, 0, 'null', audioKbps).length);
  return template.bareBytes.get(audioKbps);
}
