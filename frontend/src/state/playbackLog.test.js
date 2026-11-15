import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures } from '../test/mockApi.jsx';
import {
  copyRawReport,
  isBrowsing,
  LOG_PAGE_SIZE,
  logQuery,
  logRow,
  mainRung,
  rawReport,
  reportDetails,
  watchedText,
} from './playbackLog.js';

afterEach(() => vi.unstubAllGlobals());

const [HEAT_NOW, HEAT_TRAIN, BLADE_RUNNER, WICKER_MAN] = fixtures.adminPlaybackHealth.playbacks;

/** A fixture row with some report figures replaced. */
const withReport = (row, over) => {
  const copy = structuredClone(row);
  Object.assign(copy.report, over);
  return copy;
};

describe('logQuery', () => {
  it('sends only the paging for All', () => {
    expect(logQuery('all', 0)).toEqual({ page: 0, size: LOG_PAGE_SIZE });
    expect(LOG_PAGE_SIZE).toBe(50);
  });

  it('asks the server for film sessions with the three-minute minimum', () => {
    expect(logQuery('films', 2)).toEqual({ minWatchedMs: 180_000, page: 2, size: 50 });
  });

  it('asks the server for the plays with problems', () => {
    expect(logQuery('problems', 1)).toEqual({ problems: true, page: 1, size: 50 });
  });
});

describe('isBrowsing', () => {
  it.each([
    [179_999, true],
    [180_000, false],
    [180_001, false],
    [0, true],
  ])('a play of %s ms watched: browsing is %s', (watchedMs, browsing) => {
    expect(isBrowsing(withReport(HEAT_NOW, { watchedMs }))).toBe(browsing);
  });

  it('counts a report with no watched time as browsing, and so a row with no report', () => {
    expect(isBrowsing({ report: {} })).toBe(true);
    expect(isBrowsing({})).toBe(true);
  });
});

describe('watchedText', () => {
  it.each([
    [0, '0 s'],
    [8_400, '8 s'],
    [59_999, '59 s'],
    [60_000, '1m 0s'],
    [70_000, '1m 10s'],
    [3_599_999, '59m 59s'],
    [3_600_000, '1h 0m'],
    [9_660_000, '2h 41m'],
    [undefined, '0 s'],
  ])('%s ms reads %s', (ms, text) => {
    expect(watchedText(ms)).toBe(text);
  });
});

describe('logRow', () => {
  it('words a short, quiet play as AR4 does', () => {
    expect(logRow(HEAT_NOW)).toEqual({
      id: '7d1e4a92-3b5c-4f08-a6d1-9c2e0b8f4a17',
      film: 'Heat',
      started: '14 Nov 11:58',
      browsing: true,
      watched: '1m 58s',
      where: 'Home · Mac',
      start: '0.9 s',
      stalls: 0,
      stalled: false,
      quality: '720p',
    });
  });

  it('words a long play with stalls and two rungs', () => {
    expect(logRow(HEAT_TRAIN)).toMatchObject({
      browsing: false,
      watched: '1h 12m',
      where: 'Train · Mac',
      start: '2.4 s',
      stalls: 2,
      stalled: true,
      quality: '360p',
    });
  });

  it('names an iPhone and Original', () => {
    expect(logRow(BLADE_RUNNER)).toMatchObject({
      where: 'Home · iPhone',
      quality: 'Original',
      stalled: false,
    });
  });

  it('shows a dash where nothing started or was watched', () => {
    expect(logRow(WICKER_MAN)).toMatchObject({
      where: 'Unknown · Windows',
      start: '—',
      watched: '0 s',
      quality: '—',
      browsing: true,
    });
  });

  it('copes with a row with no report', () => {
    expect(
      logRow({ playbackId: 'x', filmTitle: 'Heat', startedAt: HEAT_NOW.startedAt }),
    ).toMatchObject({
      browsing: true,
      watched: '0 s',
      start: '—',
      stalls: 0,
      quality: '—',
    });
  });
});

describe('mainRung', () => {
  it('names the only rung', () => {
    expect(mainRung({ watchedMs: 100_000, rungMs: { '720p': 100_000 } })).toBe('720p');
    expect(mainRung({ watchedMs: 100_000, rungMs: { original: 100_000 } })).toBe('Original');
  });

  it('names the rung with the most time, whatever order the report lists them in', () => {
    expect(mainRung({ watchedMs: 100_000, rungMs: { '480p': 34_000, '360p': 66_000 } })).toBe(
      '360p',
    );
    expect(mainRung({ watchedMs: 100_000, rungMs: { '360p': 40_000, original: 60_000 } })).toBe(
      'Original',
    );
  });

  it('gives a tie to the rung earlier in the ladder', () => {
    expect(mainRung({ watchedMs: 100_000, rungMs: { '360p': 50_000, '720p': 50_000 } })).toBe(
      '720p',
    );
  });

  it('is a dash when nothing was watched or no rung holds 1%', () => {
    expect(mainRung({ watchedMs: 0, rungMs: { original: 1000 } })).toBe('—');
    expect(mainRung({ watchedMs: 100_000, rungMs: {} })).toBe('—');
  });
});

describe('reportDetails', () => {
  it('words the four lines of a play with stalls', () => {
    expect(reportDetails(HEAT_TRAIN)).toEqual([
      {
        key: 'player',
        label: 'Player',
        value: 'hls.js · quality Auto · Mac · 1512×982',
      },
      {
        key: 'network',
        label: 'Network',
        value: 'Train · Virgin Media Limited · 48 ms · 0.4 to 2.4 Mbit/s, typically 0.9',
      },
      {
        key: 'quality',
        label: 'Quality',
        value: '480p 34% · 360p 66% · 1 step down · 0 up',
      },
      {
        key: 'interruptions',
        label: 'Interruptions',
        value: '2 stalls, 9 s · 1 seek wait · 0 reloads · 1 reconnect · 4 errors',
      },
    ]);
  });

  it('words a native, quiet play and names the quality the viewer chose', () => {
    const [player, network, quality, interruptions] = reportDetails(BLADE_RUNNER);
    expect(player.value).toBe('native player · quality Original · iPhone · 390×844');
    expect(network.value).toBe('Home · 4 ms · no speed samples');
    expect(quality.value).toBe('Original 100% · 0 steps down · 0 up');
    expect(interruptions.value).toBe(
      '0 stalls · 0 seek waits · 0 reloads · 0 reconnects · 0 errors',
    );
  });

  it('leaves out what the report lacks and says so for a play that never started', () => {
    const [player, network, quality] = reportDetails(WICKER_MAN);
    expect(player.value).toBe('quality Auto · Windows · 1920×1080');
    expect(network.value).toBe('Unknown · no speed samples');
    expect(quality.value).toBe('no quality recorded · 0 steps down · 0 up');
  });

  it('shows an engine it does not know as it came, and the fall back to direct play', () => {
    const row = withReport(HEAT_NOW, { engine: 'shaka', fellBack: true, reconnectCount: 1 });
    const [player, , , interruptions] = reportDetails(row);
    expect(player.value.startsWith('shaka · ')).toBe(true);
    expect(interruptions.value).toBe(
      '0 stalls · 0 seek waits · 0 reloads · 1 reconnect · 0 errors · fell back to direct play',
    );
  });

  it('counts timeouts with the errors and says one error in the singular', () => {
    const row = withReport(HEAT_NOW, { netErrors: 0, timeouts: 1, stepsDown: 1, stepsUp: 2 });
    const [, , quality, interruptions] = reportDetails(row);
    expect(interruptions.value).toContain('1 error');
    expect(interruptions.value).not.toContain('1 errors');
    expect(quality.value).toBe('720p 100% · 1 step down · 2 up');
  });

  it('never holds raw JSON, only words', () => {
    for (const row of fixtures.adminPlaybackHealth.playbacks) {
      for (const { value } of reportDetails(row)) {
        expect(value).not.toMatch(/[{}"[\]]/);
      }
    }
  });

  it('copes with a row with no report', () => {
    expect(reportDetails({}).map((line) => line.key)).toEqual([
      'player',
      'network',
      'quality',
      'interruptions',
    ]);
  });
});

describe('rawReport and copyRawReport', () => {
  it('is the stored report as indented JSON, and {} when there is none', () => {
    expect(rawReport(HEAT_NOW)).toBe(JSON.stringify(HEAT_NOW.report, null, 2));
    expect(rawReport({})).toBe('{}');
  });

  it('writes it to the clipboard and says so', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    await expect(copyRawReport(HEAT_NOW)).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledExactlyOnceWith(JSON.stringify(HEAT_NOW.report, null, 2));
  });

  it('says false when the clipboard refuses', async () => {
    vi.stubGlobal('navigator', {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
    });
    await expect(copyRawReport(HEAT_NOW)).resolves.toBe(false);
  });

  it('says false when the browser has no clipboard', async () => {
    vi.stubGlobal('navigator', {});
    await expect(copyRawReport(HEAT_NOW)).resolves.toBe(false);
  });
});
