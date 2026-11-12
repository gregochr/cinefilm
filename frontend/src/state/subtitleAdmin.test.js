import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  FILE_FILTERS,
  MAX_OFFSET_MS,
  NUDGES,
  PLAY_LEAD_MS,
  checkSummary,
  cueTime,
  editionLabel,
  fileFilterCounts,
  fileName,
  filterSidecars,
  filmLabel,
  formatOffset,
  groupKey,
  isServed,
  nudgedOffset,
  partOptions,
  placement,
  playFromHereMs,
  playerPath,
  reportLine,
  sidecarGroups,
  subtitleProblem,
  warningsFor,
} from './subtitleAdmin.js';

const file = (over = {}) => ({
  id: 1,
  path: 'Taken (2008)/Taken.en.forced.srt',
  presence: 'PRESENT',
  state: 'matched',
  filmId: 7,
  filmTitle: 'Taken',
  editionId: 70,
  editionName: 'Original',
  parts: 1,
  part: 1,
  whole: false,
  ownerAttached: false,
  cueCount: 12,
  parseReport: 'No problems found',
  offsetMs: 0,
  verified: false,
  endsAfterRuntime: false,
  served: true,
  ...over,
});

describe('nudgedOffset', () => {
  it('adds the nudge', () => {
    expect(nudgedOffset(0, 250)).toBe(250);
    expect(nudgedOffset(1000, -250)).toBe(750);
    expect(nudgedOffset(-250, -1000)).toBe(-1250);
  });

  it('stops at ten minutes either way, one millisecond inside, exactly at and past', () => {
    expect(MAX_OFFSET_MS).toBe(600000);
    expect(nudgedOffset(MAX_OFFSET_MS - 251, 250)).toBe(MAX_OFFSET_MS - 1);
    expect(nudgedOffset(MAX_OFFSET_MS - 250, 250)).toBe(MAX_OFFSET_MS);
    expect(nudgedOffset(MAX_OFFSET_MS - 249, 250)).toBe(MAX_OFFSET_MS);
    expect(nudgedOffset(-MAX_OFFSET_MS + 249, -250)).toBe(-MAX_OFFSET_MS);
    expect(nudgedOffset(-MAX_OFFSET_MS, -1000)).toBe(-MAX_OFFSET_MS);
  });

  it('the four nudges are ±0.25 s and ±1 s, earliest first', () => {
    expect(NUDGES.map((n) => [n.label, n.delta])).toEqual([
      ['−1 s', -1000],
      ['−0.25 s', -250],
      ['+0.25 s', 250],
      ['+1 s', 1000],
    ]);
  });
});

describe('formatOffset', () => {
  it('says plainly when there is no change', () => {
    expect(formatOffset(0)).toBe('No timing change');
  });

  it('signs the offset with a plus or a true minus, in seconds without trailing zeros', () => {
    expect(formatOffset(250)).toBe('+0.25 s');
    expect(formatOffset(1000)).toBe('+1 s');
    expect(formatOffset(-1250)).toBe('−1.25 s');
    expect(formatOffset(-500)).toBe('−0.5 s');
    expect(formatOffset(600000)).toBe('+600 s');
  });
});

describe('play from here', () => {
  it('opens the player three seconds before the cue', () => {
    expect(PLAY_LEAD_MS).toBe(3000);
    expect(playFromHereMs({ startMs: 2196778 })).toBe(2193778);
  });

  it('never goes before the start, exactly at and one millisecond inside the lead', () => {
    expect(playFromHereMs({ startMs: 3001 })).toBe(1);
    expect(playFromHereMs({ startMs: 3000 })).toBe(0);
    expect(playFromHereMs({ startMs: 2999 })).toBe(0);
    expect(playFromHereMs({ startMs: 0 })).toBe(0);
  });

  it('builds the player address with the film, the edition and the time', () => {
    expect(playerPath(file({ filmId: 7, editionId: 70 }), 2193778)).toBe(
      '/films/7/play?edition=70&at=2193778',
    );
  });

  it('shows a cue’s start as a clock on the edition’s timeline', () => {
    expect(cueTime({ startMs: 2196778 })).toBe('00:36:36');
    expect(cueTime({ startMs: 3_723_004 })).toBe('01:02:03');
  });
});

describe('fileName', () => {
  it('drops the folders', () => {
    expect(fileName('Taken (2008)/Taken.en.forced.srt')).toBe('Taken.en.forced.srt');
    expect(fileName('Heat.en.forced.srt')).toBe('Heat.en.forced.srt');
  });
});

describe('grouping', () => {
  it('puts a missing file under Missing whatever its state', () => {
    expect(groupKey(file({ presence: 'MISSING' }))).toBe('missing');
    expect(groupKey(file({ presence: 'MISSING', state: 'unmatched' }))).toBe('missing');
  });

  it('otherwise groups by state', () => {
    expect(groupKey(file({ state: 'unmatched' }))).toBe('unmatched');
    expect(groupKey(file({ state: 'conflict' }))).toBe('conflicts');
    expect(groupKey(file())).toBe('matched');
  });

  it('lists Unmatched, Conflicts, Missing, Matched in that order and leaves empty groups out', () => {
    const groups = sidecarGroups([
      file({ id: 1 }),
      file({ id: 2, state: 'unmatched' }),
      file({ id: 3, presence: 'MISSING' }),
      file({ id: 4, state: 'conflict' }),
      file({ id: 5, state: 'unmatched' }),
    ]);

    expect(groups.map((g) => [g.title, g.items.map((i) => i.id)])).toEqual([
      ['Unmatched', [2, 5]],
      ['Conflicts', [4]],
      ['Missing', [3]],
      ['Matched', [1]],
    ]);
    expect(sidecarGroups([file()]).map((g) => g.key)).toEqual(['matched']);
    expect(sidecarGroups([])).toEqual([]);
  });
});

describe('placement', () => {
  it('says an unmatched file is not attached', () => {
    expect(placement(file({ filmId: null, state: 'unmatched' }))).toBe('Not attached to a film');
  });

  it('names the film and edition of a single-file edition with no part', () => {
    expect(placement(file())).toBe('Taken · Original');
  });

  it('names the whole film or the part of a split edition', () => {
    expect(placement(file({ parts: 2, whole: true, part: null, editionName: 'Extended' }))).toBe(
      'Taken · Extended · whole film',
    );
    expect(placement(file({ parts: 2, part: 2, editionName: 'Extended' }))).toBe(
      'Taken · Extended · part 2',
    );
  });
});

describe('reportLine', () => {
  it('counts the cues and says how the file was read', () => {
    expect(reportLine(file())).toBe('12 cues · No problems found');
    expect(reportLine(file({ cueCount: 1, parseReport: '3 blocks skipped' }))).toBe(
      '1 cue · 3 blocks skipped',
    );
    expect(reportLine(file({ cueCount: 0, parseReport: 'no usable cues' }))).toBe(
      '0 cues · no usable cues',
    );
  });
});

describe('warningsFor', () => {
  it('has none for a file that is fine', () => {
    expect(warningsFor(file())).toEqual([]);
  });

  it('says a missing file was not found', () => {
    expect(warningsFor(file({ presence: 'MISSING' }))).toEqual([
      'The last scan did not find this file.',
    ]);
  });

  it('says a conflict plays neither', () => {
    expect(warningsFor(file({ state: 'conflict' }))).toEqual([
      'Another file competes for the same place, so neither plays. Attach one of them elsewhere.',
    ]);
  });

  it('asks whether it is the wrong film or cut when it ends after the film', () => {
    expect(warningsFor(file({ endsAfterRuntime: true }))).toEqual([
      'Ends after the film: wrong film or cut?',
    ]);
  });

  it('lists every warning that applies', () => {
    expect(
      warningsFor(file({ presence: 'MISSING', state: 'conflict', endsAfterRuntime: true })),
    ).toHaveLength(3);
  });
});

describe('where in an edition a file can go', () => {
  it('offers only the whole film for a single file', () => {
    expect(partOptions({ parts: 1 })).toEqual([{ value: null, label: 'Whole film' }]);
  });

  it('offers the whole film and each part of a split edition', () => {
    expect(partOptions({ parts: 2 })).toEqual([
      { value: null, label: 'Whole film' },
      { value: 1, label: 'Part 1' },
      { value: 2, label: 'Part 2' },
    ]);
  });

  it('labels an edition with its file count only when it is split', () => {
    expect(editionLabel({ name: 'Theatrical Cut', parts: 1 })).toBe('Theatrical Cut');
    expect(editionLabel({ name: 'Extended Edition', parts: 2 })).toBe('Extended Edition · 2 files');
  });

  it('labels a film with its year when it has one', () => {
    expect(filmLabel({ title: 'Heat', year: 1995 })).toBe('Heat (1995)');
    expect(filmLabel({ title: 'Heat', year: null })).toBe('Heat');
  });
});

describe('subtitleProblem', () => {
  it('shows the server’s own message for a rejected request', () => {
    expect(
      subtitleProblem(
        new ApiError(400, undefined, { error: 'The offset must be within 10 minutes' }),
      ),
    ).toBe('The offset must be within 10 minutes');
  });

  it('has a plain line for a rejected request with no message', () => {
    expect(subtitleProblem(new ApiError(400))).toBe(
      'That was not accepted. Check it and try again.',
    );
  });

  it('says it is gone for a 404', () => {
    expect(subtitleProblem(new ApiError(404))).toBe(
      'That is no longer there. Reload the list and look again.',
    );
  });

  it('is generic for anything else, a network failure included', () => {
    expect(subtitleProblem(new ApiError(500))).toBe('Something went wrong. Try again.');
    expect(subtitleProblem(new TypeError('Failed to fetch'))).toBe(
      'Something went wrong. Try again.',
    );
  });
});

const checkOf = (over = {}) => ({
  verdict: 'AGREES',
  reasons: [],
  bestOffsetMs: 0,
  matchedShare: 0.9,
  servedCount: 12,
  referenceCount: 13,
  referenceRelease: 'Taken.2008.720p.BluRay',
  checkedAt: '2026-11-12T06:10:00Z',
  ...over,
});

describe('isServed', () => {
  it('is true only when the server says playback serves the file', () => {
    expect(isServed(file())).toBe(true);
    expect(isServed(file({ served: false }))).toBe(false);
    expect(isServed(file({ served: undefined }))).toBe(false);
  });
});

describe('checkSummary', () => {
  it('says AGREES with the server worded timing hint and the reference line', () => {
    const summary = checkSummary(
      file({
        check: checkOf({ reasons: ["OpenSubtitles' file is 1.2 s later"], bestOffsetMs: 1200 }),
      }),
    );
    expect(summary).toEqual({
      kind: 'agrees',
      label: 'Agrees with OpenSubtitles',
      lines: ["OpenSubtitles' file is 1.2 s later"],
      reference: 'Compared with Taken.2008.720p.BluRay · checked 12 Nov 06:10',
    });
  });

  it('shows the server hint as it is, and builds none from the best offset', () => {
    const hint = "OpenSubtitles' file is 1.2 s earlier";
    expect(
      checkSummary(file({ check: checkOf({ reasons: [hint], bestOffsetMs: -1200 }) })).lines,
    ).toEqual([hint]);
    expect(checkSummary(file({ check: checkOf({ bestOffsetMs: -1500 }) })).lines).toEqual([]);
  });

  it('has no hint line for a file in sync', () => {
    expect(checkSummary(file({ check: checkOf() })).lines).toEqual([]);
  });

  it('lists the reasons of a CHECK, hint included', () => {
    const reasons = [
      'OpenSubtitles has 41 lines, this file has 12',
      "OpenSubtitles' file is 2.0 s later",
    ];
    const summary = checkSummary(file({ check: checkOf({ verdict: 'CHECK', reasons }) }));
    expect(summary.kind).toBe('check');
    expect(summary.label).toBe('Needs checking');
    expect(summary.lines).toEqual(reasons);
  });

  it('copes with a CHECK with no reasons list, no release and no time', () => {
    const summary = checkSummary(
      file({
        check: checkOf({
          verdict: 'CHECK',
          reasons: undefined,
          referenceRelease: null,
          checkedAt: null,
        }),
      }),
    );
    expect(summary.lines).toEqual([]);
    expect(summary.reference).toBeNull();
  });

  it('shows only the time when the release is not known, and only the release when there is no time', () => {
    expect(checkSummary(file({ check: checkOf({ referenceRelease: null }) })).reference).toBe(
      'checked 12 Nov 06:10',
    );
    expect(checkSummary(file({ check: checkOf({ checkedAt: null }) })).reference).toBe(
      'Compared with Taken.2008.720p.BluRay',
    );
  });

  it('says Unchecked, quietly, for a served file with no check', () => {
    expect(checkSummary(file({ check: null }))).toEqual({
      kind: 'unchecked',
      label: 'Unchecked',
      lines: ['No OpenSubtitles file to compare with yet'],
      reference: null,
    });
    expect(checkSummary(file())).toMatchObject({ kind: 'unchecked' });
  });

  it('says nothing for a file playback does not serve', () => {
    expect(checkSummary(file({ served: false, check: null }))).toBeNull();
    expect(checkSummary(file({ state: 'conflict', served: false, check: null }))).toBeNull();
  });
});

describe('file filters', () => {
  const items = [
    file({ id: 1, check: checkOf() }),
    file({ id: 2, check: checkOf({ verdict: 'CHECK' }), verified: true }),
    file({ id: 3, check: null }),
    file({ id: 4, state: 'unmatched', served: false, check: null }),
    file({ id: 5, check: checkOf({ verdict: 'CHECK' }) }),
  ];

  it('are All, Needs checking and Unchecked, in that order', () => {
    expect(FILE_FILTERS.map((f) => f.label)).toEqual(['All', 'Needs checking', 'Unchecked']);
  });

  it('lists the files of each chip; a verified file that needs checking still counts', () => {
    expect(filterSidecars(items, 'all').map((i) => i.id)).toEqual([1, 2, 3, 4, 5]);
    expect(filterSidecars(items, 'check').map((i) => i.id)).toEqual([2, 5]);
    expect(filterSidecars(items, 'unchecked').map((i) => i.id)).toEqual([3]);
  });

  it('lists everything for an unknown key', () => {
    expect(filterSidecars(items, 'nope')).toHaveLength(5);
  });

  it('counts each chip', () => {
    expect(fileFilterCounts(items)).toEqual({ all: 5, check: 2, unchecked: 1 });
    expect(fileFilterCounts([])).toEqual({ all: 0, check: 0, unchecked: 0 });
  });
});
