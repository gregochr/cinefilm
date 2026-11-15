import { describe, expect, it } from 'vitest';
import { describeDevice } from './userAgent.js';

const SAFARI_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15';
const CHROME_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const CHROME_WIN =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const EDGE_WIN =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.2478.67';
const OPERA_WIN =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 OPR/110.0.0.0';
const FIREFOX_LINUX = 'Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0';
const CHROME_LINUX =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const CHROME_OS =
  'Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1';
const IPHONE_CHROME =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/124.0.6367.88 Mobile/15E148 Safari/604.1';
const IPAD =
  'Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1';
const ANDROID =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const FIREFOX_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:125.0) Gecko/20100101 Firefox/125.0';

describe('describeDevice', () => {
  it.each([
    ['Safari on a Mac', SAFARI_MAC, 'Safari on Mac'],
    ['Chrome on a Mac', CHROME_MAC, 'Chrome on Mac'],
    ['Chrome on Windows', CHROME_WIN, 'Chrome on Windows'],
    ['Edge, not Chrome', EDGE_WIN, 'Edge on Windows'],
    ['Opera, not Chrome', OPERA_WIN, 'Opera on Windows'],
    ['Firefox on Linux', FIREFOX_LINUX, 'Firefox on Linux'],
    ['Firefox on a Mac', FIREFOX_MAC, 'Firefox on Mac'],
    ['Chrome on Linux', CHROME_LINUX, 'Chrome on Linux'],
    ['Chrome on a Chromebook', CHROME_OS, 'Chrome on ChromeOS'],
    ['an iPhone, whatever its "Mac OS X"', IPHONE, 'iPhone'],
    ['an iPhone running Chrome', IPHONE_CHROME, 'iPhone'],
    ['an iPad', IPAD, 'iPad'],
    ['an Android phone, whatever its "Linux"', ANDROID, 'Android'],
  ])('names %s', (_name, userAgent, label) => {
    expect(describeDevice(userAgent)).toBe(label);
  });

  // Pins today's behaviour: iPadOS Safari sends a desktop Mac user agent, which the user agent alone cannot tell
  // from a Mac. The player's touch hint (admin-redesign-plan 5a) is what will.
  it('reads an iPad that sends the desktop user agent as a Mac', () => {
    expect(describeDevice(SAFARI_MAC)).toBe('Safari on Mac');
  });

  it('gives the platform alone when the browser is not recognised (the fixture)', () => {
    expect(describeDevice('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')).toBe('Mac');
  });

  it('gives the browser alone when the platform is not recognised', () => {
    expect(describeDevice('Mozilla/5.0 (Unknown) Gecko/20100101 Firefox/125.0')).toBe('Firefox');
  });

  it.each([[null], [undefined], [''], [42], ['curl/8.4.0']])(
    'falls back to "Unknown device" for %j',
    (userAgent) => {
      expect(describeDevice(userAgent)).toBe('Unknown device');
    },
  );
});
