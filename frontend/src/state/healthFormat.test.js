import { describe, expect, it } from 'vitest';
import { fixtures } from '../test/mockApi.jsx';
import {
  FILM_SESSION_MIN_MS,
  deviceLabel,
  describePlayback,
  environmentLabel,
  networkLine,
  operatorName,
  playLine,
  qualityLine,
  rungShares,
  speedText,
  stallDuration,
  startedLine,
} from './healthFormat.js';

const row = (report = {}, extra = {}) => ({
  playbackId: 'a91e5b07-4d3c-42f8-8e6a-7c1b9d0f5e22',
  filmTitle: 'Heat',
  startedAt: '2026-11-10T19:43:05Z',
  report,
  ...extra,
});

describe('FILM_SESSION_MIN_MS', () => {
  it('is three minutes, as on the server', () => {
    expect(FILM_SESSION_MIN_MS).toBe(180_000);
  });
});

describe('environmentLabel', () => {
  it('names the three environments and calls anything else Unknown', () => {
    expect(environmentLabel('home')).toBe('Home');
    expect(environmentLabel('train')).toBe('Train');
    expect(environmentLabel('away')).toBe('Away');
    expect(environmentLabel('unknown')).toBe('Unknown');
    expect(environmentLabel('hotel')).toBe('Unknown');
    expect(environmentLabel(undefined)).toBe('Unknown');
    expect(environmentLabel(null)).toBe('Unknown');
  });

  it('does not take a property of Object for an environment', () => {
    expect(environmentLabel('toString')).toBe('Unknown');
    expect(environmentLabel('constructor')).toBe('Unknown');
  });
});

describe('operatorName', () => {
  it('drops the leading AS number', () => {
    expect(operatorName('AS5089 Virgin Media Limited')).toBe('Virgin Media Limited');
    expect(operatorName('AS7 X')).toBe('X');
    expect(operatorName('AS64496   Example Rail Wi-Fi')).toBe('Example Rail Wi-Fi');
  });

  it('keeps a value without that prefix as it is', () => {
    expect(operatorName('Virgin Media Limited')).toBe('Virgin Media Limited');
    expect(operatorName('AS5089Virgin')).toBe('AS5089Virgin');
    expect(operatorName('ASX 5089 Thing')).toBe('ASX 5089 Thing');
    expect(operatorName('Thing AS5089 Limited')).toBe('Thing AS5089 Limited');
    expect(operatorName('as5089 Thing')).toBe('as5089 Thing');
  });

  it('gives null for nothing, blank, or a prefix with no name', () => {
    expect(operatorName(null)).toBeNull();
    expect(operatorName(undefined)).toBeNull();
    expect(operatorName('')).toBeNull();
    expect(operatorName('   ')).toBeNull();
    expect(operatorName('AS5089 ')).toBeNull();
    expect(operatorName(42)).toBeNull();
  });
});

describe('deviceLabel', () => {
  it('finds each platform word', () => {
    expect(deviceLabel('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1)')).toBe('iPhone');
    expect(deviceLabel('Mozilla/5.0 (iPad; CPU OS 18_1)')).toBe('iPad');
    expect(deviceLabel('Mozilla/5.0 (Linux; Android 14; Pixel 8)')).toBe('Android');
    expect(deviceLabel('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('Windows');
    expect(deviceLabel('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')).toBe('Mac');
    expect(deviceLabel('Mozilla/5.0 (X11; Linux x86_64)')).toBe('Linux');
  });

  it('takes the first word of the list that is present, not the first in the string', () => {
    expect(deviceLabel('Mozilla/5.0 (iPhone; like Mac OS X)')).toBe('iPhone');
    expect(deviceLabel('Linux Android')).toBe('Android');
    expect(deviceLabel('Linux Windows Mac')).toBe('Windows');
    expect(deviceLabel('Linux Mac')).toBe('Mac');
    expect(deviceLabel('Mac iPad')).toBe('iPad');
  });

  it('is case sensitive and says Unknown device for anything else or nothing', () => {
    expect(deviceLabel('Mozilla/5.0 (X11; CrOS)')).toBe('Unknown device');
    expect(deviceLabel('iphone')).toBe('Unknown device');
    expect(deviceLabel('')).toBe('Unknown device');
    expect(deviceLabel(undefined)).toBe('Unknown device');
    expect(deviceLabel(null)).toBe('Unknown device');
    expect(deviceLabel(7)).toBe('Unknown device');
  });
});

describe('stallDuration', () => {
  it('writes whole rounded seconds under a minute', () => {
    expect(stallDuration(0)).toBe('0 s');
    expect(stallDuration(499)).toBe('0 s');
    expect(stallDuration(500)).toBe('1 s');
    expect(stallDuration(6000)).toBe('6 s');
    expect(stallDuration(9400)).toBe('9 s');
    expect(stallDuration(59_499)).toBe('59 s');
  });

  it('writes minutes and seconds from 60 s, counting the rounded seconds', () => {
    expect(stallDuration(59_500)).toBe('1m 0s');
    expect(stallDuration(60_000)).toBe('1m 0s');
    expect(stallDuration(125_000)).toBe('2m 5s');
    expect(stallDuration(3_599_000)).toBe('59m 59s');
    expect(stallDuration(3_600_000)).toBe('60m 0s');
  });

  it('treats a missing figure as 0', () => {
    expect(stallDuration(undefined)).toBe('0 s');
    expect(stallDuration(null)).toBe('0 s');
  });
});

describe('startedLine', () => {
  const mac = { client: { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' } };

  it('reads start, time watched and device', () => {
    expect(startedLine(row({ ...mac, watchedMs: 4_320_000 }))).toBe(
      '10 Nov 19:43 · 1h 12m watched · Mac',
    );
  });

  it('says under 1m below 60,000 ms and writes minutes from 60,000', () => {
    expect(startedLine(row({ ...mac, watchedMs: 59_999 }))).toBe(
      '10 Nov 19:43 · under 1m watched · Mac',
    );
    expect(startedLine(row({ ...mac, watchedMs: 60_000 }))).toBe('10 Nov 19:43 · 1m watched · Mac');
    expect(startedLine(row({ ...mac, watchedMs: 0 }))).toBe(
      '10 Nov 19:43 · under 1m watched · Mac',
    );
  });

  it('copes with a report that has no watched time or client', () => {
    expect(startedLine(row({}))).toBe('10 Nov 19:43 · under 1m watched · Unknown device');
    expect(startedLine({ startedAt: '2026-11-10T19:43:05Z' })).toBe(
      '10 Nov 19:43 · under 1m watched · Unknown device',
    );
  });
});

describe('playLine', () => {
  const played = { startupMs: 2380, stallCount: 0 };

  it('reads the start to one decimal', () => {
    expect(playLine(row(played))).toBe('Start 2.4 s · No stalls');
    expect(playLine(row({ ...played, startupMs: 900 }))).toBe('Start 0.9 s · No stalls');
    expect(playLine(row({ ...played, startupMs: 0 }))).toBe('Start 0.0 s · No stalls');
    expect(playLine(row({ ...played, startupMs: 12_340 }))).toBe('Start 12.3 s · No stalls');
  });

  it('says Not played yet or Did not start when there is no start', () => {
    expect(playLine(row({ startupMs: null, autoplayRefused: true }))).toBe(
      'Not played yet · No stalls',
    );
    expect(playLine(row({ startupMs: null, autoplayRefused: false }))).toBe(
      'Did not start · No stalls',
    );
    expect(playLine(row({ startupMs: null }))).toBe('Did not start · No stalls');
    expect(playLine(row({}))).toBe('Did not start · No stalls');
  });

  it('shows a start even when autoplay was refused', () => {
    expect(playLine(row({ ...played, autoplayRefused: true }))).toBe('Start 2.4 s · No stalls');
  });

  it('counts one stall and several', () => {
    expect(playLine(row({ ...played, stallCount: 1, stallMs: 6000 }))).toBe(
      'Start 2.4 s · 1 stall, 6 s',
    );
    expect(playLine(row({ ...played, stallCount: 2, stallMs: 9400 }))).toBe(
      'Start 2.4 s · 2 stalls, 9 s',
    );
    expect(playLine(row({ ...played, stallCount: 3, stallMs: 125_000 }))).toBe(
      'Start 2.4 s · 3 stalls, 2m 5s',
    );
  });

  it('switches to minutes at a stall total of 60 s, after rounding', () => {
    const line = (stallMs) => playLine(row({ ...played, stallCount: 2, stallMs }));
    expect(line(59_499)).toBe('Start 2.4 s · 2 stalls, 59 s');
    expect(line(59_500)).toBe('Start 2.4 s · 2 stalls, 1m 0s');
    expect(line(60_000)).toBe('Start 2.4 s · 2 stalls, 1m 0s');
  });

  it('adds reconnects only when there are some', () => {
    const line = (reconnectCount) => playLine(row({ ...played, reconnectCount }));
    expect(line(0)).toBe('Start 2.4 s · No stalls');
    expect(line(1)).toBe('Start 2.4 s · No stalls · 1 reconnect');
    expect(line(2)).toBe('Start 2.4 s · No stalls · 2 reconnects');
  });

  it('adds the fallback last, only when it happened', () => {
    expect(playLine(row({ ...played, fellBack: true }))).toBe(
      'Start 2.4 s · No stalls · fell back to direct play',
    );
    expect(playLine(row({ ...played, fellBack: false }))).toBe('Start 2.4 s · No stalls');
    expect(
      playLine(row({ ...played, stallCount: 2, stallMs: 9400, reconnectCount: 1, fellBack: true })),
    ).toBe('Start 2.4 s · 2 stalls, 9 s · 1 reconnect · fell back to direct play');
  });
});

describe('rungShares', () => {
  it('names each rung with its rounded share of the watched time', () => {
    expect(rungShares({ watchedMs: 100_000, rungMs: { '480p': 34_000, '360p': 66_000 } })).toEqual([
      '480p 34%',
      '360p 66%',
    ]);
    expect(rungShares({ watchedMs: 1000, rungMs: { original: 1000 } })).toEqual(['Original 100%']);
  });

  it('rounds the percentage to the nearest whole number', () => {
    expect(rungShares({ watchedMs: 1000, rungMs: { '720p': 334, '480p': 666 } })).toEqual([
      '720p 33%',
      '480p 67%',
    ]);
    expect(rungShares({ watchedMs: 1000, rungMs: { '720p': 335 } })).toEqual(['720p 34%']);
  });

  it('leaves out a rung under 1% and keeps one at exactly 1%', () => {
    expect(rungShares({ watchedMs: 100_000, rungMs: { '480p': 900, '360p': 99_100 } })).toEqual([
      '360p 99%',
    ]);
    expect(rungShares({ watchedMs: 100_000, rungMs: { '480p': 1000, '360p': 99_000 } })).toEqual([
      '480p 1%',
      '360p 99%',
    ]);
  });

  it('keeps ladder order whatever order the report lists the rungs in', () => {
    const rungMs = {
      '240p': 1000,
      '360p': 2000,
      '480p': 3000,
      '720p': 4000,
      '1080p': 5000,
      original: 6000,
    };
    expect(rungShares({ watchedMs: 21_000, rungMs })).toEqual([
      'Original 29%',
      '1080p 24%',
      '720p 19%',
      '480p 14%',
      '360p 10%',
      '240p 5%',
    ]);
  });

  it('ignores a rung id the ladder does not have, and copes with nothing', () => {
    expect(rungShares({ watchedMs: 1000, rungMs: { '144p': 1000, '480p': 500 } })).toEqual([
      '480p 50%',
    ]);
    expect(rungShares({ watchedMs: 1000 })).toEqual([]);
    expect(rungShares({ watchedMs: 0, rungMs: { '480p': 500 } })).toEqual([]);
    expect(rungShares({ rungMs: { '480p': 500 } })).toEqual([]);
  });
});

describe('speedText', () => {
  it('writes the speeds in Mbit/s to one decimal', () => {
    expect(speedText({ kbpsSamples: 41, kbpsLow: 420, kbpsMedian: 910, kbpsHigh: 2400 })).toBe(
      '0.4 to 2.4 Mbit/s, typically 0.9',
    );
    expect(speedText({ kbpsSamples: 1, kbpsLow: 1250, kbpsMedian: 12_340, kbpsHigh: 20_000 })).toBe(
      '1.3 to 20.0 Mbit/s, typically 12.3',
    );
    expect(speedText({ kbpsSamples: 1, kbpsLow: 0, kbpsMedian: 40, kbpsHigh: 49 })).toBe(
      '0.0 to 0.0 Mbit/s, typically 0.0',
    );
  });

  it('says no speed samples for 0, null or a missing count', () => {
    expect(speedText({ kbpsSamples: 0, kbpsLow: 420, kbpsMedian: 910, kbpsHigh: 2400 })).toBe(
      'no speed samples',
    );
    expect(speedText({ kbpsSamples: null })).toBe('no speed samples');
    expect(speedText({})).toBe('no speed samples');
  });

  it('treats a missing speed beside samples as 0', () => {
    expect(speedText({ kbpsSamples: 3, kbpsLow: null })).toBe('0.0 to 0.0 Mbit/s, typically 0.0');
  });
});

describe('qualityLine', () => {
  it('joins the rungs and the speeds', () => {
    expect(
      qualityLine(
        row({
          watchedMs: 100_000,
          rungMs: { '480p': 34_000, '360p': 66_000 },
          kbpsSamples: 41,
          kbpsLow: 420,
          kbpsMedian: 910,
          kbpsHigh: 2400,
        }),
      ),
    ).toBe('480p 34% · 360p 66% · 0.4 to 2.4 Mbit/s, typically 0.9');
  });

  it('is the speed text alone when no rung holds 1%', () => {
    expect(qualityLine(row({ watchedMs: 100_000, rungMs: { '480p': 900 } }))).toBe(
      'no speed samples',
    );
    expect(qualityLine(row({ watchedMs: 5000 }))).toBe('no speed samples');
  });

  it('is null when nothing was watched or the figure is missing', () => {
    expect(qualityLine(row({ watchedMs: 0, rungMs: { '480p': 1 } }))).toBeNull();
    expect(qualityLine(row({}))).toBeNull();
    expect(qualityLine({})).toBeNull();
  });
});

describe('networkLine', () => {
  it('joins the operator name and the latency', () => {
    expect(networkLine(row({ operator: 'AS5089 Virgin Media Limited', latencyMs: 48 }))).toBe(
      'Virgin Media Limited · 48 ms',
    );
  });

  it('shows either part alone', () => {
    expect(networkLine(row({ operator: 'AS5089 Virgin Media Limited', latencyMs: null }))).toBe(
      'Virgin Media Limited',
    );
    expect(networkLine(row({ operator: null, latencyMs: 4 }))).toBe('4 ms');
    expect(networkLine(row({ operator: '  ', latencyMs: 0 }))).toBe('0 ms');
  });

  it('is null when neither is known', () => {
    expect(networkLine(row({ operator: null, latencyMs: null }))).toBeNull();
    expect(networkLine(row({}))).toBeNull();
    expect(networkLine({})).toBeNull();
  });
});

describe('describePlayback', () => {
  const [train, home, unplayed] = fixtures.adminPlaybackHealth.playbacks;

  it('gives all four lines for a train viewing', () => {
    expect(describePlayback(train)).toEqual([
      '10 Nov 19:43 · 1h 12m watched · Mac',
      'Start 2.4 s · 2 stalls, 9 s · 1 reconnect',
      '480p 34% · 360p 66% · 0.4 to 2.4 Mbit/s, typically 0.9',
      'Virgin Media Limited · 48 ms',
    ]);
  });

  it('gives a clean home viewing on an iPhone', () => {
    expect(describePlayback(home)).toEqual([
      '09 Nov 18:10 · 1h 51m watched · iPhone',
      'Start 0.9 s · No stalls',
      'Original 100% · no speed samples',
      '4 ms',
    ]);
  });

  it('leaves out the lines with nothing to say for a viewing that has not played', () => {
    expect(describePlayback(unplayed)).toEqual([
      '08 Nov 21:15 · under 1m watched · Windows',
      'Not played yet · No stalls',
    ]);
  });

  it('copes with a report that has every field missing', () => {
    expect(describePlayback(row({}))).toEqual([
      '10 Nov 19:43 · under 1m watched · Unknown device',
      'Did not start · No stalls',
    ]);
  });
});
