// scripts/replay/ts.mjs on a small template of made-up bytes: the segment's size, its tables, time stamps,
// continuity counters, padding and silence, all read back out of the bytes with a parser written here.
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, test } from 'node:test';
import { bareSegmentBytes, buildSegment, loadTemplate } from '../../replay/ts.mjs';
import { SILENCE, makeTemplate } from './support.mjs';

const PACKET = 188;
const VIDEO = 0x100;
const AUDIO = 0x101;
const NULL_PID = 0x1fff;
const BASE_PTS = 900000;
const SEGMENT_TICKS = 360000; // eight frames at two a second: four seconds of 90 kHz ticks

const tmp = mkdtempSync(join(tmpdir(), 'replay-'));
after(() => rmSync(tmp, { recursive: true, force: true }));

const template = makeTemplate();
const mute = makeTemplate({ silence: null });

const packetsOf = (segment) => {
  const out = [];
  for (let offset = 0; offset < segment.length; offset += PACKET) out.push(segment.subarray(offset, offset + PACKET));
  return out;
};
const pidOf = (packet) => ((packet[1] & 0x1f) << 8) | packet[2];
const startsPes = (packet) => (packet[1] & 0x40) !== 0;
const counterOf = (packet) => packet[3] & 0x0f;
const payloadOf = (packet) => packet.subarray((packet[3] & 0x20) === 0 ? 4 : 5 + packet[4]);
const ofPid = (segment, pid) => packetsOf(segment).filter((p) => pidOf(p) === pid);

// Each PES packet of one stream, put back together.
function pesOf(segment, pid) {
  const out = [];
  for (const packet of ofPid(segment, pid)) {
    if (startsPes(packet)) out.push([]);
    out[out.length - 1].push(payloadOf(packet));
  }
  return out.map((parts) => Buffer.concat(parts));
}

// A 33-bit time stamp from its five bytes, checking the prefix and the three marker bits.
function stampAt(bytes, offset, prefix) {
  const [b0, b1, b2, b3, b4] = bytes.subarray(offset, offset + 5);
  assert.equal(b0 >> 4, prefix);
  assert.equal(b0 & 1, 1);
  assert.equal(b2 & 1, 1);
  assert.equal(b4 & 1, 1);
  return ((b0 >> 1) & 7) * 2 ** 30 + b1 * 2 ** 22 + (b2 >> 1) * 2 ** 15 + b3 * 2 ** 7 + (b4 >> 1);
}

// MPEG-2's CRC-32, bit by bit (the code under test uses a table).
function mpeg2Crc(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte << 24;
    for (let bit = 0; bit < 8; bit += 1) crc = crc & 0x80000000 ? (crc << 1) ^ 0x04c11db7 : crc << 1;
  }
  return crc >>> 0;
}

// A table packet's section: after the pointer field, up to and including its four check bytes.
function sectionOf(packet) {
  const length = ((packet[6] & 0x0f) << 8) | packet[7];
  return packet.subarray(5, 8 + length);
}

const videoPts = (segment) => pesOf(segment, VIDEO).map((pes) => stampAt(pes, 9, 2));
const audioPts = (segment) => pesOf(segment, AUDIO).map((pes) => stampAt(pes, 9, 2));
const silenceFrames = (segment) => pesOf(segment, AUDIO).reduce((sum, pes) => sum + (pes.readUInt16BE(4) - 8) / SILENCE.length, 0);
const nullCount = (segment) => ofPid(segment, NULL_PID).length;

describe('the size of a segment', () => {
  test('is the target rounded down to whole 188-byte packets, with either padding', () => {
    assert.equal(buildSegment(template, 0, 100000, 'media').length, 99828); // 531 packets
    assert.equal(buildSegment(template, 0, 100000, 'null').length, 99828);
    assert.equal(buildSegment(template, 0, 100015, 'null').length, 99828);
    assert.equal(buildSegment(template, 0, 100016, 'null').length, 100016); // 532 packets
    assert.equal(buildSegment(template, 5, 100016, 'media').length, 100016);
  });

  test('is never smaller than its frames and silence need, whatever the target', () => {
    assert.equal(bareSegmentBytes(template), 8272); // 44 packets: two tables, 18 of picture and 24 of silence
    assert.equal(buildSegment(template, 0, 0, 'null').length, 8272);
    assert.equal(buildSegment(template, 0, 1000, 'null').length, 8272);
    assert.equal(buildSegment(template, 0, 1000, 'media').length, 8272);
    assert.equal(buildSegment(template, 0, 8271, 'media').length, 8272);
    assert.equal(bareSegmentBytes(mute), 3760); // 20 packets with no silence
  });

  test('is a whole number of packets, each beginning 0x47', () => {
    for (const fill of ['media', 'null']) {
      const segment = buildSegment(template, 2, 54321, fill);
      assert.equal(segment.length % PACKET, 0);
      assert.deepEqual(packetsOf(segment).filter((p) => p[0] !== 0x47), []);
    }
  });

  test('is the same bytes however often it is built, and each index differs only in its time stamps', () => {
    const a = buildSegment(template, 3, 60000, 'media');
    assert.ok(a.equals(buildSegment(template, 3, 60000, 'media')));
    assert.equal(buildSegment(template, 4, 60000, 'media').length, a.length);
    assert.ok(!a.equals(buildSegment(template, 4, 60000, 'media')));
  });
});

describe('the tables', () => {
  test('come first: the programme association table, then the programme map', () => {
    const [pat, pmt] = packetsOf(buildSegment(template, 0, 20000, 'media'));
    assert.equal(pidOf(pat), 0);
    assert.equal(pidOf(pmt), 0x1000);
    assert.equal(startsPes(pat), true);
    assert.equal(counterOf(pat), 0);
    assert.equal(counterOf(pmt), 0);
  });

  test('the association table is the standard one for programme 1 on PID 0x1000, with its check bytes', () => {
    const [pat] = packetsOf(buildSegment(template, 0, 20000, 'media'));
    assert.equal(pat.subarray(0, 21).toString('hex'), '474000100000b00d0001c100000001f0002ab104b2');
    assert.ok(pat.subarray(21).every((byte) => byte === 0xff));
  });

  test('the programme map lists the picture alone when there is no silence', () => {
    const [, pmt] = packetsOf(buildSegment(mute, 0, 20000, 'media'));
    assert.equal(pmt.subarray(0, 26).toString('hex'), '475000100002b0120001c10000e100f0001be100f00015bd4d56');
    assert.ok(pmt.subarray(26).every((byte) => byte === 0xff));
    assert.equal(ofPid(buildSegment(mute, 0, 20000, 'media'), AUDIO).length, 0);
  });

  test('the programme map lists the picture and the silence when there is silence', () => {
    const [, pmt] = packetsOf(buildSegment(template, 0, 20000, 'media'));
    assert.equal(pmt.subarray(0, 31).toString('hex'), '475000100002b0170001c10000e100f0001be100f0000fe101f0002f44b99b');
    assert.ok(pmt.subarray(31).every((byte) => byte === 0xff));
  });

  test('each table ends in the check bytes of its own section', () => {
    for (const source of [template, mute]) {
      const [pat, pmt] = packetsOf(buildSegment(source, 0, 20000, 'null'));
      for (const packet of [pat, pmt]) {
        const section = sectionOf(packet);
        assert.equal(mpeg2Crc(section.subarray(0, -4)), section.readUInt32BE(section.length - 4));
      }
    }
  });
});

describe('time stamps', () => {
  test('every frame of segment 0 is stamped from the base, one frame apart', () => {
    const stamps = videoPts(buildSegment(template, 0, 100000, 'media'));
    assert.deepEqual(stamps, [0, 1, 2, 3, 4, 5, 6, 7].map((i) => BASE_PTS + i * 45000));
  });

  test('segment 1 starts one segment later', () => {
    const stamps = videoPts(buildSegment(template, 1, 100000, 'media'));
    assert.deepEqual(stamps, [0, 1, 2, 3, 4, 5, 6, 7].map((i) => BASE_PTS + SEGMENT_TICKS + i * 45000));
  });

  test('a segment past 2^32 ticks is stamped in all 33 bits', () => {
    const index = 11928; // 900000 + 11928 x 360000 = 4,294,980,000: the first segment above 4,294,967,296
    const stamps = videoPts(buildSegment(template, index, 50000, 'media'));
    assert.equal(stamps[0], 4294980000);
    assert.equal(stamps[7], 4294980000 + 7 * 45000);
    assert.ok(stamps[0] > 2 ** 32);
    assert.equal(videoPts(buildSegment(template, index - 1, 50000, 'media'))[0], 4294620000); // the segment before, still under
  });

  test('the silence runs on its own unbroken clock across segments', () => {
    assert.deepEqual(audioPts(buildSegment(template, 0, 50000, 'null')).slice(0, 3), [BASE_PTS, BASE_PTS + 15360, BASE_PTS + 30720]);
    // Segment 1 carries frames 188 onwards: 188 x 1920 ticks after the base.
    assert.equal(audioPts(buildSegment(template, 1, 50000, 'null'))[0], BASE_PTS + 360960);
  });
});

describe('continuity counters', () => {
  test('rise by one, modulo 16, on each stream, from 0 in every segment', () => {
    const segment = buildSegment(template, 7, 100000, 'media');
    for (const pid of [VIDEO, AUDIO]) {
      const counters = ofPid(segment, pid).map(counterOf);
      assert.ok(counters.length > 16, `stream ${pid} wraps the counter`);
      assert.deepEqual(counters, counters.map((_, i) => i % 16));
    }
  });

  test('the picture keeps one count across all its frames, not one per frame', () => {
    const counters = ofPid(buildSegment(template, 0, 20000, 'null'), VIDEO).map(counterOf);
    assert.equal(counters.length, 18);
    assert.deepEqual(counters, counters.map((_, i) => i % 16));
  });
});

describe('padding', () => {
  test('null padding adds only null packets after the frames, and counts exactly', () => {
    const segment = buildSegment(template, 0, 100000, 'null');
    assert.equal(nullCount(segment), 487); // 531 packets less the 44 the frames and silence need
    assert.equal(ofPid(segment, VIDEO).length, 18);
    const frame = pesOf(segment, VIDEO)[0];
    assert.equal(frame.length, 20 + template.frames[0].data.length);
    assert.ok(frame.subarray(20).equals(template.frames[0].data));
  });

  test('media padding puts a filler into every frame and only a few null packets after', () => {
    const segment = buildSegment(template, 0, 100000, 'media');
    assert.equal(nullCount(segment), 6);
    const frames = pesOf(segment, VIDEO);
    assert.equal(frames.length, 8);
    frames.forEach((pes, i) => {
      const data = template.frames[i].data;
      // header (9) + stamp (5) + access unit delimiter (6), then the filler, then the frame
      assert.equal(pes.subarray(14, 20).toString('hex'), '0000000109f0');
      assert.equal(pes.subarray(20, 26).toString('hex'), '000000010603');
      assert.ok(pes.subarray(pes.length - data.length).equals(data));
      const filler = pes.subarray(26, pes.length - data.length);
      // the size: 0xff for each 255, then the remainder; then that many 0xff bytes; then the closing 0x80
      let sizeBytes = 0;
      let size = 0;
      while (filler[sizeBytes] === 0xff) {
        size += 255;
        sizeBytes += 1;
      }
      size += filler[sizeBytes];
      sizeBytes += 1;
      assert.equal(size, 11020);
      assert.equal(filler.length, sizeBytes + size + 1);
      assert.ok(filler.subarray(sizeBytes, sizeBytes + size).every((b) => b === 0xff));
      assert.equal(filler[filler.length - 1], 0x80);
    });
  });

  test('a target too small for a filler leaves the frames bare', () => {
    const segment = buildSegment(template, 0, 5000, 'media');
    assert.equal(segment.length, 8272);
    assert.equal(pesOf(segment, VIDEO)[0].length, 20 + template.frames[0].data.length);
  });
});

describe('silence', () => {
  test('a four-second template carries 188 frames of silence in segment 0 and 187 in segment 1', () => {
    assert.equal(silenceFrames(buildSegment(template, 0, 50000, 'null')), 188);
    assert.equal(silenceFrames(buildSegment(template, 1, 50000, 'null')), 187);
    assert.equal(silenceFrames(buildSegment(template, 2, 50000, 'null')), 188);
    assert.equal(silenceFrames(buildSegment(template, 3, 50000, 'null')), 187);
  });

  test('is packed eight frames to a PES packet, the last one with what is left', () => {
    const lengths = pesOf(buildSegment(template, 0, 50000, 'null'), AUDIO).map((pes) => (pes.readUInt16BE(4) - 8) / SILENCE.length);
    assert.equal(lengths.length, 24);
    assert.deepEqual(lengths.slice(0, 23), new Array(23).fill(8));
    assert.equal(lengths[23], 4);
  });

  test('each frame of it is the template\'s silence, byte for byte', () => {
    const [first] = pesOf(buildSegment(template, 0, 50000, 'null'), AUDIO);
    assert.ok(first.subarray(14, 14 + SILENCE.length).equals(SILENCE));
    assert.ok(first.subarray(14 + SILENCE.length, 14 + 2 * SILENCE.length).equals(SILENCE));
  });

  test('a template with no silence has no sound stream', () => {
    const segment = buildSegment(mute, 0, 50000, 'media');
    assert.equal(ofPid(segment, AUDIO).length, 0);
    assert.equal(videoPts(segment).length, 8);
  });
});

describe('loadTemplate', () => {
  const frames = [Buffer.from('first frame'), Buffer.from('second'), Buffer.from('third one here')];
  const write = (name, body) => {
    const path = join(tmp, name);
    writeFileSync(path, JSON.stringify(body));
    return path;
  };
  const body = (extra) => ({
    codec: 'avc1.64001e', fps: 4, width: 640, height: 360,
    frames: frames.map((data, i) => ({ key: i === 0, b64: data.toString('base64') })), ...extra,
  });

  test('reads the frames, the silence and the length from a file', () => {
    const loaded = loadTemplate(write('with-silence.json', body({ silence: SILENCE.toString('base64') })));
    assert.equal(loaded.codec, 'avc1.64001e');
    assert.equal(loaded.fps, 4);
    assert.equal(loaded.width, 640);
    assert.equal(loaded.height, 360);
    assert.equal(loaded.seconds, 0.75); // three frames at four a second
    assert.deepEqual(loaded.frames.map((f) => f.key), [true, false, false]);
    assert.deepEqual(loaded.frames.map((f) => f.data.toString()), ['first frame', 'second', 'third one here']);
    assert.ok(loaded.silence.equals(SILENCE));
  });

  test('has no silence when the file has none', () => {
    assert.equal(loadTemplate(write('no-silence.json', body({}))).silence, null);
    assert.equal(loadTemplate(write('null-silence.json', body({ silence: null }))).silence, null);
  });

  test('a loaded template builds a segment', () => {
    const loaded = loadTemplate(write('buildable.json', body({ silence: SILENCE.toString('base64') })));
    assert.equal(buildSegment(loaded, 0, 20000, 'media').length, 19928);
  });
});
