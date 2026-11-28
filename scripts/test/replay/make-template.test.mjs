// scripts/replay/make-template.mjs: what can be tested without Chrome. `silentFrame` decides whether the silence
// Chrome's encoder made is one ADTS frame of AAC-LC at 48 kHz in stereo, and `nalTypes` reads the NAL units of the
// picture. The encoding itself, and the template's file, are checked by hand.
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, test } from 'node:test';
import { TEMPLATE_PATH, nalTypes, noiseFrames, silentFrame } from '../../replay/make-template.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

// A good ADTS header (seven bytes: sync 0xfff, layer 0, AAC-LC, 48 kHz, stereo, one block) and three bytes of
// payload: ten bytes, the length the header gives.
const GOOD = [0xff, 0xf1, 0x4c, 0x80, 0x01, 0x5f, 0xfc, 0x01, 0x02, 0x03];

// An encoder's output of `count` frames with `frame` as the one in the middle, as base64 texts.
function audioWith(frame, count = 20) {
  const filler = Buffer.from(GOOD).toString('base64');
  const frames = new Array(count).fill(filler);
  frames[Math.floor(count / 2)] = Buffer.from(frame).toString('base64');
  return { frames };
}

// GOOD with one byte changed.
function changed(index, value) {
  const copy = [...GOOD];
  copy[index] = value;
  return copy;
}

describe('silentFrame', () => {
  test('a good frame from the middle of the encoder\'s output is returned, byte for byte', () => {
    assert.deepEqual(silentFrame(audioWith(GOOD)), Buffer.from(GOOD));
  });

  test('it is the middle frame that is taken, not the first or the last', () => {
    const audio = audioWith(changed(0, 0x00));
    audio.frames[0] = Buffer.from(GOOD).toString('base64');
    audio.frames[19] = Buffer.from(GOOD).toString('base64');
    assert.equal(silentFrame(audio), null);
  });

  test('no audio, an error instead of frames, or frames that are not a list give null', () => {
    assert.equal(silentFrame(undefined), null);
    assert.equal(silentFrame(null), null);
    assert.equal(silentFrame({ error: 'no AudioEncoder' }), null);
    assert.equal(silentFrame({ frames: 'abc' }), null);
  });

  test('20 frames are enough to skip the encoder\'s start-up, 19 are not', () => {
    assert.notEqual(silentFrame(audioWith(GOOD, 20)), null);
    assert.equal(silentFrame(audioWith(GOOD, 19)), null);
  });

  test('a frame without the ADTS sync word is refused', () => {
    assert.equal(silentFrame(audioWith(changed(0, 0xfe))), null);
    assert.equal(silentFrame(audioWith(changed(1, 0x01))), null);
  });

  test('a frame whose layer bits are not 0 is refused, and one with or without a checksum is not', () => {
    assert.equal(silentFrame(audioWith(changed(1, 0xf3))), null);
    assert.notEqual(silentFrame(audioWith(changed(1, 0xf0))), null);
  });

  test('a profile that is not AAC-LC is refused (main profile here)', () => {
    assert.equal(silentFrame(audioWith(changed(2, 0x0c | 0x00))), null);
  });

  test('a sample rate that is not 48 kHz is refused (44.1 kHz here)', () => {
    assert.equal(silentFrame(audioWith(changed(2, 0x40 | (4 << 2)))), null);
  });

  test('a channel count that is not 2 is refused (mono here)', () => {
    assert.equal(silentFrame(audioWith(changed(3, 0x40))), null);
  });

  test('a length in the header that is not the frame\'s own length is refused', () => {
    assert.equal(silentFrame(audioWith(changed(5, 0x7f))), null);
  });

  test('more than one block in the frame is refused', () => {
    assert.equal(silentFrame(audioWith(changed(6, 0xfd))), null);
  });

  test('a frame of under eight bytes is refused even when its header agrees with its length', () => {
    const short = [0xff, 0xf1, 0x4c, 0x80, 0x00, 0xff, 0xfc];
    assert.equal(silentFrame(audioWith(short)), null);
  });
});

describe('nalTypes', () => {
  test('lists the type of each NAL unit after a three- or four-byte start code', () => {
    const buffer = Buffer.from([0, 0, 0, 1, 0x67, 0xaa, 0, 0, 1, 0x68, 0xbb, 0, 0, 0, 1, 0x65, 0x88]);
    assert.deepEqual(nalTypes(buffer), [7, 8, 5]);
  });

  test('takes the type from the low five bits of the byte after the start code', () => {
    assert.deepEqual(nalTypes(Buffer.from([0, 0, 1, 0xe9])), [9]);
  });

  test('a start code with nothing after it, and bytes with no start code, give nothing', () => {
    assert.deepEqual(nalTypes(Buffer.from([5, 0, 0, 1])), []);
    assert.deepEqual(nalTypes(Buffer.from([1, 2, 3, 4, 5])), []);
    assert.deepEqual(nalTypes(Buffer.alloc(0)), []);
  });
});

describe('TEMPLATE_PATH', () => {
  test('is scripts/replay/out/template.json in this checkout, a folder git ignores', () => {
    assert.equal(TEMPLATE_PATH, join(HERE, '..', '..', 'replay', 'out', 'template.json'));
  });
});

// One ADTS frame (AAC-LC, 48 kHz, stereo, one block) of `length` bytes in all, its header giving that length.
function adts(length) {
  const frame = Buffer.alloc(length, 0x5a);
  Buffer.from([0xff, 0xf1, 0x4c, 0x80 | (length >> 11), (length >> 3) & 0xff, ((length & 7) << 5) | 0x1f, 0xfc]).copy(frame);
  return frame;
}

// What the encoder hands back for `kbps`: `count` frames of the length that rate averages (1024 samples at 48 kHz).
function noiseAt(kbps, count = 420, length = Math.round((kbps * 1000 / 8) * (1024 / 48000))) {
  return { frames: Array.from({ length: count }, () => adts(length).toString('base64')) };
}

describe('noiseFrames', () => {
  test('frames at the rate asked for are returned, byte for byte', () => {
    const frames = noiseFrames(96, noiseAt(96));
    assert.equal(frames.length, 420);
    assert.deepEqual(frames[0], adts(256));
  });

  test('the rate may be 15% off, and no more', () => {
    assert.equal(noiseFrames(96, noiseAt(96, 420, 256 * 1.14)).length, 420);
    assert.equal(noiseFrames(96, noiseAt(96, 420, 256 * 0.86)).length, 420);
    assert.throws(() => noiseFrames(96, noiseAt(96, 420, 256 * 1.2)), { message: /the encoder made 115 kbit\/s/ });
    assert.throws(() => noiseFrames(96, noiseAt(96, 420, 256 * 0.8)), { message: /the encoder made 77 kbit\/s/ });
  });

  test('each frame is checked: one that is not an ADTS frame of AAC-LC at 48 kHz in stereo is refused, named by place', () => {
    const entry = noiseAt(96);
    const bad = adts(256);
    bad[2] = 0x40 | (4 << 2); // 44.1 kHz
    entry.frames[7] = bad.toString('base64');
    assert.throws(() => noiseFrames(96, entry), { message: 'noise at 96 kbit/s: frame 7 is not one ADTS frame of AAC-LC at 48 kHz in stereo' });
    const short = noiseAt(96);
    short.frames[3] = adts(255).subarray(0, 250).toString('base64'); // the header says 255
    assert.throws(() => noiseFrames(96, short), { message: /frame 3 is not one ADTS frame/ });
  });

  test('a count other than 420, an error, or nothing at all is refused, with the encoder\'s words', () => {
    assert.throws(() => noiseFrames(96, noiseAt(96, 419)), { message: 'noise at 96 kbit/s: 419 frames, not 420' });
    assert.throws(() => noiseFrames(64, { error: 'AAC is not supported' }), { message: 'noise at 64 kbit/s: AAC is not supported' });
    assert.throws(() => noiseFrames(64, undefined), { message: 'noise at 64 kbit/s: no frames' });
    assert.throws(() => noiseFrames(64, { frames: 'abc' }), { message: 'noise at 64 kbit/s: no frames' });
  });
});
