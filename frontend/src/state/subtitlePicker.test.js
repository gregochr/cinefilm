import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import { fixtures } from '../test/mockApi.jsx';
import { pickerProblem, resultDetails, resultName } from './subtitlePicker.js';

const [full, sdh] = fixtures.adminOpenSubtitles.full.results;
const [forced, unnamed, bare] = fixtures.adminOpenSubtitles.forced.results;

describe('resultName', () => {
  it('is the release name, else the file name, else the file id', () => {
    expect(resultName(full)).toBe('Heat.1995.Directors.Cut.BluRay.x264');
    expect(resultName({ ...full, release: null })).toBe('Heat.1995.Directors.Cut.BluRay.x264.srt');
    expect(resultName({ ...full, release: '', fileName: null })).toBe('File 6012');
    expect(resultName(unnamed)).toBe('Heat.1995.1080p.BluRay');
  });
});

describe('resultDetails', () => {
  it('lists downloads, frame rate, trusted uploader and upload year, in that order', () => {
    expect(resultDetails(forced)).toEqual([
      '4,210 downloads',
      '23.976 fps',
      'Trusted uploader',
      'Uploaded 2019',
    ]);
  });

  it('adds "Hearing impaired" after the trusted flag and leaves out what the server did not send', () => {
    expect(resultDetails(bare)).toEqual(['12 downloads', 'Hearing impaired']);
    expect(resultDetails({ ...sdh, fromTrusted: true })).toEqual([
      '18,800 downloads',
      '23.976 fps',
      'Trusted uploader',
      'Hearing impaired',
      'Uploaded 2017',
    ]);
  });

  it('says "1 download" for one, and shows an upload date that is not a year as nothing', () => {
    expect(resultDetails({ ...bare, hearingImpaired: false, downloadCount: 1 })).toEqual([
      '1 download',
    ]);
    expect(resultDetails({ ...bare, uploadDate: 'yesterday', hearingImpaired: false })).toEqual([
      '12 downloads',
    ]);
  });

  it('shows a frame rate of 0 rather than treating it as missing', () => {
    expect(resultDetails({ ...bare, fps: 0, hearingImpaired: false })).toEqual([
      '12 downloads',
      '0 fps',
    ]);
  });
});

describe('pickerProblem', () => {
  const failure = (status, error) => new ApiError(status, undefined, error ? { error } : null);

  it.each([
    [400, 'That file was not in a recent search for this edition. Search again.'],
    [409, 'A scan is running'],
    [422, 'That subtitle file could not be read.'],
    [429, 'OpenSubtitles daily limit reached — try again tomorrow.'],
    [
      502,
      "OpenSubtitles counted this download against today's limit but could not deliver the file. Try again.",
    ],
    [503, 'OpenSubtitles is not answering. Try again in a few minutes.'],
  ])("is the server's exact text for a %i", (status, text) => {
    expect(pickerProblem(failure(status, text), 'download')).toBe(text);
    expect(pickerProblem(failure(status, text), 'search')).toBe(text);
  });

  it('falls back to the daily-limit words for a 429 with no text', () => {
    expect(pickerProblem(failure(429), 'download')).toBe(
      'OpenSubtitles daily limit reached — try again tomorrow.',
    );
  });

  it('falls back to a plain line per phase for any other failure, and ignores a blank error text', () => {
    expect(pickerProblem(failure(500), 'search')).toBe('Couldn’t search OpenSubtitles. Try again.');
    expect(pickerProblem(failure(500), 'download')).toBe('Couldn’t download that file. Try again.');
    expect(pickerProblem(failure(502, '  '), 'download')).toBe(
      'Couldn’t download that file. Try again.',
    );
    expect(pickerProblem(new TypeError('Failed to fetch'), 'download')).toBe(
      'Couldn’t download that file. Try again.',
    );
  });
});
