import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import { FULL_KEY, SUBTITLE_OFF } from '../player/tracks.js';
import {
  Changed,
  LookupFailed,
  fileTools,
  filmFiles,
  shownDownloadKind,
  shownFileKind,
  shownTargets,
  wrongProblem,
  wrongTargets,
} from './subtitleFix.js';
import { subtitlesWrong } from './subtitleFixCopy.js';

const row = (over = {}) => ({
  id: 1,
  filmId: 6,
  editionId: 61,
  kind: 'FORCED',
  origin: 'OPENSUBTITLES',
  served: true,
  ownerAttached: false,
  verified: false,
  ...over,
});

describe('filmFiles', () => {
  it('keeps the rows that sit under the film, in the server’s order, and leaves the unattached out', () => {
    const rows = [
      row({ id: 1 }),
      row({ id: 2, filmId: 7 }),
      row({ id: 3, filmId: null }),
      row({ id: 4 }),
    ];

    expect(filmFiles(rows, 6).map((r) => r.id)).toEqual([1, 4]);
  });
});

describe('fileTools', () => {
  it('offers "Use my file instead" only for an owner file a download replaced that is not yet chosen', () => {
    expect(fileTools(row({ replacedByDownload: true })).useMine).toBe(true);
    expect(fileTools(row({ replacedByDownload: true, verified: true })).useMine).toBe(false);
    expect(fileTools(row({ replacedByDownload: false })).useMine).toBe(false);
    expect(fileTools(row({})).useMine).toBe(false);
  });

  it('offers "Let CineFilm decide" only for a file the owner attached by hand', () => {
    expect(fileTools(row({ ownerAttached: true })).letDecide).toBe(true);
    expect(fileTools(row({ ownerAttached: false })).letDecide).toBe(false);
  });

  it('offers "Delete this download" only for an OpenSubtitles download, never the owner’s file', () => {
    expect(fileTools(row({ origin: 'OPENSUBTITLES' })).deleteDownload).toBe(true);
    expect(fileTools(row({ origin: 'FILE' })).deleteDownload).toBe(false);
  });

  it('always offers "Preview lines" and has no "Looks right"', () => {
    const tools = fileTools(row({ origin: 'FILE' }));

    expect(tools.preview).toBe(true);
    expect(Object.keys(tools).sort()).toEqual([
      'deleteDownload',
      'letDecide',
      'preview',
      'useMine',
    ]);
  });
});

describe('shownDownloadKind', () => {
  const base = { streams: [], forcedSubtitle: null, fullSubtitle: null };
  const download = { source: 'DOWNLOAD', version: 1 };
  const sidecar = { source: 'SIDECAR', version: 1 };

  it('names FULL while the chosen full subtitle is a download', () => {
    expect(shownDownloadKind({ ...base, choice: FULL_KEY, fullSubtitle: download })).toBe('FULL');
  });

  it('names FORCED while Off shows the forced subtitle and it is a download', () => {
    expect(shownDownloadKind({ ...base, choice: SUBTITLE_OFF, forcedSubtitle: download })).toBe(
      'FORCED',
    );
  });

  it('names nothing for the owner’s files', () => {
    expect(shownDownloadKind({ ...base, choice: FULL_KEY, fullSubtitle: sidecar })).toBeNull();
    expect(
      shownDownloadKind({ ...base, choice: SUBTITLE_OFF, forcedSubtitle: sidecar }),
    ).toBeNull();
  });

  it('names nothing when a stream is chosen, even if the edition has downloaded subtitles', () => {
    expect(
      shownDownloadKind({
        choice: 113,
        streams: [{ id: 113, index: 3, language: 'English', forced: false }],
        forcedSubtitle: download,
        fullSubtitle: download,
      }),
    ).toBeNull();
  });

  it('names the forced download, not the full one, while Off is chosen', () => {
    expect(
      shownDownloadKind({
        ...base,
        choice: SUBTITLE_OFF,
        forcedSubtitle: download,
        fullSubtitle: download,
      }),
    ).toBe('FORCED');
  });

  it('names nothing when Off shows nothing', () => {
    expect(shownDownloadKind({ ...base, choice: SUBTITLE_OFF, fullSubtitle: download })).toBeNull();
  });
});

describe('shownFileKind', () => {
  const base = { streams: [], forcedSubtitle: null, fullSubtitle: null };
  const download = { source: 'DOWNLOAD', version: 1 };
  const sidecar = { source: 'SIDECAR', version: 1 };

  it.each([
    ['the owner’s full file', { choice: FULL_KEY, fullSubtitle: sidecar }, 'FULL'],
    ['a downloaded full file', { choice: FULL_KEY, fullSubtitle: download }, 'FULL'],
    [
      'the owner’s forced file while Off',
      { choice: SUBTITLE_OFF, forcedSubtitle: sidecar },
      'FORCED',
    ],
    [
      'a downloaded forced file while Off',
      { choice: SUBTITLE_OFF, forcedSubtitle: download },
      'FORCED',
    ],
  ])('names the kind for %s', (_name, over, kind) => {
    expect(shownFileKind({ ...base, ...over })).toBe(kind);
  });

  it('names nothing for an embedded stream, even when files exist', () => {
    expect(
      shownFileKind({
        choice: 113,
        streams: [{ id: 113, index: 3, language: 'English', forced: false }],
        forcedSubtitle: download,
        fullSubtitle: sidecar,
      }),
    ).toBeNull();
  });

  it('names nothing when Off shows nothing, or the full choice has no full file', () => {
    expect(shownFileKind({ ...base, choice: SUBTITLE_OFF, fullSubtitle: sidecar })).toBeNull();
    expect(shownFileKind({ ...base, choice: FULL_KEY })).toBeNull();
  });
});

describe('wrongTargets', () => {
  const want = { filmId: 6, editionId: 61, kind: 'FORCED' };

  it('picks the download that plays for that edition and kind', () => {
    expect(wrongTargets([row({ id: 6 })], want).map((r) => r.id)).toEqual([6]);
  });

  it.each([
    ['another film', { filmId: 7 }],
    ['another edition', { editionId: 62 }],
    ['another kind', { kind: 'FULL' }],
    ['a download that is not playing', { served: false }],
    ['the owner’s file', { origin: 'FILE' }],
  ])('leaves out %s', (_name, over) => {
    expect(wrongTargets([row(over)], want)).toEqual([]);
  });
});

describe('wrongProblem', () => {
  it('shows the server’s own words for a refused request', () => {
    const text = 'Only a subtitle downloaded from OpenSubtitles can be removed';

    expect(wrongProblem(new ApiError(400, text, { error: text }))).toBe(text);
  });

  it('says the subtitle changed when another download plays now', () => {
    expect(wrongProblem(new Changed())).toBe(subtitlesWrong.changed);
  });

  it('says it could not find the download when no row matched', () => {
    expect(wrongProblem(new LookupFailed())).toBe(subtitlesWrong.lookupFailed);
  });

  it.each([
    [
      'a 409 while a scan runs',
      new ApiError(409, 'A scan is running', { error: 'A scan is running' }),
    ],
    ['a 400 with no text', new ApiError(400, null, null)],
    ['a network failure', new TypeError('Failed to fetch')],
  ])('says one plain line for %s', (_name, failure) => {
    expect(wrongProblem(failure)).toBe(subtitlesWrong.failed);
  });
});

describe('shownTargets', () => {
  const playing = [row({ id: 70 }), row({ id: 71 })];

  it('keeps only the downloads the film showed', () => {
    expect(shownTargets(playing, [71]).map((r) => r.id)).toEqual([71]);
  });

  it('keeps nothing when the film showed none of them, or carried no ids', () => {
    expect(shownTargets(playing, [5])).toEqual([]);
    expect(shownTargets(playing, undefined)).toEqual([]);
    expect(shownTargets(playing, [])).toEqual([]);
  });
});
