// scripts/replay/make-template.mjs: what can be tested without Chrome. `silentFrame` decides whether the silence
// Chrome's encoder made is one ADTS frame of AAC-LC at 48 kHz in stereo, and `nalTypes` reads the NAL units of the
// picture. The encoding itself, and the template's file, are checked by hand.
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, test } from 'node:test';
import { TEMPLATE_PATH, nalTypes, silentFrame } from '../../replay/make-template.mjs';

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
