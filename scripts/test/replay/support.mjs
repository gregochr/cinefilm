// Shared by the replay rig's tests: a small film template of made-up bytes (the real one is made by Chrome and is
// not in git), and the repository's own fixture film. Not a test file: the wrapper only runs *.test.mjs.
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

/** The app's own fixture film, which the stand-in serves (it reads the same file, so a change to it fails here). */
export const FILM_PATH = join(HERE, '..', '..', '..', 'frontend', 'src', 'test', 'fixtures', 'film-heat.json');

/** One ADTS frame's worth of made-up bytes. */
export const SILENCE = Buffer.from([0xff, 0xf1, 0x50, 0x80, 0x01, 0x1f, 0xfc, 0x01, 0x02, 0x03]);

/**
 * A template as `loadTemplate` returns it: `count` frames at `fps` (default 8 frames at 2 a second: four seconds),
 * the first of them a key frame, each of a different size so that a mix-up of frames shows.
 */
export function makeTemplate({ count = 8, fps = 2, silence = SILENCE, noise = null } = {}) {
  const frames = [];
  for (let i = 0; i < count; i += 1) {
    frames.push({ key: i === 0, data: Buffer.alloc(150 + i * 40, i + 1) });
  }
  return { codec: 'avc1.64001e', fps, width: 640, height: 360, frames, silence, noise, seconds: count / fps };
}

/**
 * Noise as `loadTemplate` returns it, for the audio rates (kbit/s) given: a list of made-up frames at each, of three
 * sizes that rise with the rate (so that a mix-up of rates shows), each frame filled with its own byte.
 */
export function makeNoise(rates = [64, 96, 128]) {
  return Object.fromEntries(rates.map((kbps) => [kbps, [0, 1, 2].map((i) => Buffer.alloc(kbps + i * 10, 0x30 + i))]));
}
